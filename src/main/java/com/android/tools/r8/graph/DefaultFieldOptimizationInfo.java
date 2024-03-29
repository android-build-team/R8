// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

public class DefaultFieldOptimizationInfo extends FieldOptimizationInfo {

  private static final DefaultFieldOptimizationInfo INSTANCE = new DefaultFieldOptimizationInfo();

  private DefaultFieldOptimizationInfo() {}

  public static DefaultFieldOptimizationInfo getInstance() {
    return INSTANCE;
  }

  @Override
  public boolean cannotBeKept() {
    return false;
  }

  @Override
  public boolean valueHasBeenPropagated() {
    return false;
  }

  @Override
  public boolean isDefaultFieldOptimizationInfo() {
    return true;
  }

  @Override
  public DefaultFieldOptimizationInfo asDefaultFieldOptimizationInfo() {
    return this;
  }
}
