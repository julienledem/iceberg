/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Closeables;
import com.netflix.iceberg.exceptions.CommitFailedException;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.io.OutputFile;
import com.netflix.iceberg.util.BinPacking.ListPacker;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.netflix.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES;
import static com.netflix.iceberg.TableProperties.MANIFEST_TARGET_SIZE_BYTES_DEFAULT;

/**
 * Append implementation that produces a minimal number of manifest files.
 * <p>
 * This implementation will attempt to commit 5 times before throwing {@link CommitFailedException}.
 */
class MergeAppend extends SnapshotUpdate implements AppendFiles {
  private static final long SIZE_PER_FILE = 100; // assume each file will be ~100 bytes

  private final TableOperations ops;
  private final PartitionSpec spec;
  private final List<DataFile> newFiles = Lists.newArrayList();
  private final long manifestTargetSizeBytes;

  // cache merge results to reuse when retrying
  private final Map<List<String>, String> mergedManifests = Maps.newHashMap();

  MergeAppend(TableOperations ops) {
    super(ops);
    this.ops = ops;
    this.spec = ops.current().spec();
    this.manifestTargetSizeBytes = ops.current()
        .propertyAsLong(MANIFEST_TARGET_SIZE_BYTES, MANIFEST_TARGET_SIZE_BYTES_DEFAULT);
  }

  @Override
  public MergeAppend appendFile(DataFile file) {
    newFiles.add(file);
    return this;
  }

  @Override
  public List<String> apply(TableMetadata base) {
    Snapshot current = base.currentSnapshot();
    List<PartitionSpec> specs = Lists.newArrayList();
    List<List<ManifestReader>> groups = Lists.newArrayList();

    // add the current spec as the first group. files are added to the beginning.
    specs.add(spec);
    groups.add(Lists.newArrayList());
    groups.get(0).add(newFilesAsManifest());

    List<ManifestReader> toClose = Lists.newArrayList();
    boolean threw = true;
    try {
      // group manifests by compatible partition specs to be merged
      if (current != null) {
        for (String manifest : current.manifests()) {
          ManifestReader reader = ManifestReader.read(ops.newInputFile(manifest));
          toClose.add(reader);

          int index = findMatch(specs, reader.spec());
          if (index < 0) {
            // not found, add a new one
            List<ManifestReader> newList = Lists.<ManifestReader>newArrayList(reader);
            specs.add(reader.spec());
            groups.add(newList);
          } else {
            // replace the reader spec with the later one
            specs.set(index, reader.spec());
            groups.get(index).add(reader);
          }
        }
      }

      List<String> manifests = Lists.newArrayList();
      for (int i = 0; i < specs.size(); i += 1) {
        manifests.addAll(mergeGroup(specs.get(i), groups.get(i)));
      }

      threw = false;

      return manifests;

    } finally {
      for (ManifestReader reader : toClose) {
        try {
          Closeables.close(reader, threw);
        } catch (IOException e) {
          throw new RuntimeIOException(e);
        }
      }
    }
  }

  @Override
  protected void cleanUncommitted(Set<String> committed) {
    for (String merged: mergedManifests.values()) {
      // delete any new merged manifests that aren't in the committed list
      if (!committed.contains(merged)) {
        deleteFile(merged);
      }
    }
    mergedManifests.clear();
  }

  private List<String> mergeGroup(PartitionSpec spec, List<ManifestReader> group) {
    // use a lookback of 1 to avoid reordering the manifests. using 1 also means this should pack
    // from the end so that the manifest that gets under-filled is the first one, which will be
    // merged the next time.
    ListPacker<ManifestReader> packer = new ListPacker<>(manifestTargetSizeBytes, 1);
    List<List<ManifestReader>> bins = packer.packEnd(group,
        reader -> reader.file() != null ? reader.file().getLength() : newFiles.size() * SIZE_PER_FILE);

    List<String> outputManifests = Lists.newLinkedList();
    for (int i = 0; i < bins.size(); i += 1) {
      List<ManifestReader> bin = bins.get(i);

      if (bin.size() == 1 && bin.get(0).file() != null) {
        // no need to rewrite
        outputManifests.add(bin.get(0).file().location());
        continue;
      }

      List<String> key = cacheKey(bin);
      // if this merge was already rewritten, use the existing file.
      // if the new files are in this merge, the key is based on the number of new files so files
      // added after the last merge will cause a cache miss.
      if (mergedManifests.containsKey(key)) {
        outputManifests.add(mergedManifests.get(key));
        continue;
      }

      OutputFile out = manifestPath(mergedManifests.size());

      try (ManifestWriter writer = new ManifestWriter(spec, out, snapshotId())) {

        for (ManifestReader reader : bin) {
          if (reader.file() != null) {
            writer.addExisting(reader.entries());
          } else {
            writer.addEntries(reader.entries());
          }
        }

      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to write manifest: %s", out);
      }

      // update the cache
      mergedManifests.put(key, out.location());

      outputManifests.add(out.location());
    }

    return outputManifests;
  }

  private ManifestReader newFilesAsManifest() {
    long id = snapshotId();
    ManifestEntry reused = new ManifestEntry(spec.partitionType());
    return ManifestReader.inMemory(spec,
        Iterables.transform(newFiles, file -> {
          reused.wrapAppend(id, file);
          return reused;
        }));
  }

  private List<String> cacheKey(List<ManifestReader> group) {
    List<String> key = Lists.newArrayList();

    for (ManifestReader reader : group) {
      if (reader.file() != null) {
        key.add(reader.file().location());
      } else {
        // if the file is null, this is an in-memory reader
        // use the size to avoid collisions if retries have added files
        key.add("append-" + newFiles.size() + "-files");
      }
    }

    return key;
  }

  /**
   * Helper method to group manifests by compatible partition spec.
   * <p>
   * When a match is found, this will replace the current spec for the group with the query spec.
   * This is to produce manifests with the latest compatible spec.
   *
   * @param specs   a list of partition specs, corresponding to the groups of readers
   * @param spec    spec to be matched to a group
   * @return        group of readers files for this spec can be merged into
   */
  private static int findMatch(List<PartitionSpec> specs,
                               PartitionSpec spec) {
    // loop from last to first because later specs are most likely to match
    for (int i = specs.size() - 1; i >= 0; i -= 1) {
      if (specs.get(i).compatibleWith(spec)) {
        return i;
      }
    }

    return -1;
  }

}
