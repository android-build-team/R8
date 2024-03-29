// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.D8Command.Builder;
import com.android.tools.r8.TestBase.Backend;
import com.android.tools.r8.desugar.corelib.CoreLibDesugarTestBase.KeepRuleConsumer;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class D8TestBuilder
    extends TestCompilerBuilder<
        D8Command, Builder, D8TestCompileResult, D8TestRunResult, D8TestBuilder> {

  private D8TestBuilder(TestState state, Builder builder) {
    super(state, builder, Backend.DEX);
  }

  public static D8TestBuilder create(TestState state) {
    return new D8TestBuilder(state, D8Command.builder(state.getDiagnosticsHandler()));
  }

  @Override
  D8TestBuilder self() {
    return this;
  }

  public Builder getBuilder() {
    return builder;
  }

  public D8TestBuilder addProgramResourceProvider(ProgramResourceProvider provider) {
    builder.addProgramResourceProvider(provider);
    return self();
  }

  @Override
  public D8TestBuilder addClasspathClasses(Collection<Class<?>> classes) {
    builder.addClasspathResourceProvider(ClassFileResourceProviderFromClasses(classes));
    return self();
  }

  @Override
  public D8TestBuilder addClasspathFiles(Collection<Path> files) {
    builder.addClasspathFiles(files);
    return self();
  }

  @Override
  D8TestCompileResult internalCompile(
      Builder builder, Consumer<InternalOptions> optionsConsumer, Supplier<AndroidApp> app)
      throws CompilationFailedException {
    ToolHelper.runD8(builder, optionsConsumer);
    return new D8TestCompileResult(getState(), app.get(), getOutputMode());
  }

  public D8TestBuilder setIntermediate(boolean intermediate) {
    builder.setIntermediate(intermediate);
    return self();
  }

  @Override
  public D8TestBuilder enableCoreLibraryDesugaring(
      AndroidApiLevel minAPILevel, KeepRuleConsumer keepRuleConsumer) {
    if (minAPILevel.getLevel() < AndroidApiLevel.O.getLevel()) {
      // Use P to mimic current Android Studio.
      builder.addLibraryFiles(ToolHelper.getAndroidJar(AndroidApiLevel.P));
      builder.addSpecialLibraryConfiguration("default");
      builder.setDesugaredLibraryKeepRuleConsumer(keepRuleConsumer);
    }
    return self();
  }
}
