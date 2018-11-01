// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.graph.invokesuper.Consumer;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class R8TestCompileResult extends TestCompileResult {

  private final Backend backend;
  private final String proguardMap;

  R8TestCompileResult(TestState state, Backend backend, AndroidApp app, String proguardMap) {
    super(state, app);
    this.backend = backend;
    this.proguardMap = proguardMap;
  }

  @Override
  public Backend getBackend() {
    return backend;
  }

  @Override
  public CodeInspector inspector() throws IOException, ExecutionException {
    return new CodeInspector(app, proguardMap);
  }

  @Override
  public TestCompileResult inspect(Consumer<CodeInspector> consumer)
      throws IOException, ExecutionException {
    consumer.accept(new CodeInspector(app, proguardMap));
    return this;
  }
}