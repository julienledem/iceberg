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

import java.io.Serializable;
import java.util.Map;

import static com.google.common.collect.ImmutableMap.copyOf;

public class Metrics implements Serializable {

  private Long rowCount = null;
  private Map<Integer, Long> columnSizes = null;
  private Map<Integer, Long> valueCounts = null;
  private Map<Integer, Long> nullValueCounts = null;

  public Metrics() {
  }

  public Metrics(Long rowCount,
                 Map<Integer, Long> columnSizes,
                 Map<Integer, Long> valueCounts,
                 Map<Integer, Long> nullValueCounts) {
    this.rowCount = rowCount;
    this.columnSizes = columnSizes;
    this.valueCounts = valueCounts;
    this.nullValueCounts = nullValueCounts;
  }

  public Long recordCount() {
    return rowCount;
  }

  public Map<Integer, Long> columnSizes() {
    return columnSizes;
  }

  public Map<Integer, Long> valueCounts() {
    return valueCounts;
  }

  public Map<Integer, Long> nullValueCounts() {
    return nullValueCounts;
  }

}
