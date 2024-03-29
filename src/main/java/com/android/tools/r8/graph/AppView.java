// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.OptionalBool;
import com.android.tools.r8.graph.analysis.InitializedClassesInInstanceMethodsAnalysis.InitializedClassesInInstanceMethods;
import com.android.tools.r8.ir.analysis.proto.GeneratedExtensionRegistryShrinker;
import com.android.tools.r8.ir.analysis.proto.GeneratedMessageLiteShrinker;
import com.android.tools.r8.ir.analysis.proto.ProtoShrinker;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.shaking.VerticalClassMerger.VerticallyMergedClasses;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class AppView<T extends AppInfo> implements DexDefinitionSupplier {

  private enum WholeProgramOptimizations {
    ON,
    OFF
  }

  private T appInfo;
  private AppServices appServices;
  private final DexItemFactory dexItemFactory;
  private final WholeProgramOptimizations wholeProgramOptimizations;
  private GraphLense graphLense;
  private final InternalOptions options;
  private RootSet rootSet;

  // Optimizations.
  private final ProtoShrinker protoShrinker;

  // Optimization results.
  private Predicate<DexType> classesEscapingIntoLibrary = Predicates.alwaysTrue();
  private InitializedClassesInInstanceMethods initializedClassesInInstanceMethods;
  private Set<DexMethod> unneededVisibilityBridgeMethods = ImmutableSet.of();
  private VerticallyMergedClasses verticallyMergedClasses;

  private AppView(
      T appInfo, WholeProgramOptimizations wholeProgramOptimizations, InternalOptions options) {
    this.appInfo = appInfo;
    this.dexItemFactory = appInfo != null ? appInfo.dexItemFactory() : null;
    this.wholeProgramOptimizations = wholeProgramOptimizations;
    this.graphLense = GraphLense.getIdentityLense();
    this.options = options;

    if (enableWholeProgramOptimizations() && options.isProtoShrinkingEnabled()) {
      this.protoShrinker = new ProtoShrinker(withLiveness());
    } else {
      this.protoShrinker = null;
    }
  }

  public static <T extends AppInfo> AppView<T> createForD8(T appInfo, InternalOptions options) {
    return new AppView<>(appInfo, WholeProgramOptimizations.OFF, options);
  }

  public static <T extends AppInfo> AppView<T> createForR8(T appInfo, InternalOptions options) {
    return new AppView<>(appInfo, WholeProgramOptimizations.ON, options);
  }

  public static <T extends AppInfo> AppView<T> createForL8(T appInfo, InternalOptions options) {
    return createForD8(appInfo, options);
  }

  public T appInfo() {
    return appInfo;
  }

  public <U extends T> AppView<U> setAppInfo(U appInfo) {
    assert !appInfo.isObsolete();
    AppInfo previous = this.appInfo;
    this.appInfo = appInfo;
    if (appInfo != previous) {
      previous.markObsolete();
    }
    @SuppressWarnings("unchecked")
    AppView<U> appViewWithSpecializedAppInfo = (AppView<U>) this;
    return appViewWithSpecializedAppInfo;
  }

  public AppServices appServices() {
    return appServices;
  }

  public void setAppServices(AppServices appServices) {
    this.appServices = appServices;
  }

  public boolean isClassEscapingIntoLibrary(DexType type) {
    assert type.isClassType();
    return classesEscapingIntoLibrary.test(type);
  }

  public void setClassesEscapingIntoLibrary(Predicate<DexType> classesEscapingIntoLibrary) {
    this.classesEscapingIntoLibrary = classesEscapingIntoLibrary;
  }

  @Override
  public final DexDefinition definitionFor(DexReference reference) {
    return appInfo().definitionFor(reference);
  }

  @Override
  public final DexEncodedField definitionFor(DexField field) {
    return appInfo().definitionFor(field);
  }

  @Override
  public final DexEncodedMethod definitionFor(DexMethod method) {
    return appInfo().definitionFor(method);
  }

  @Override
  public final DexClass definitionFor(DexType type) {
    return appInfo().definitionFor(type);
  }

  public OptionalBool isInterface(DexType type) {
    // Without whole program information we should not assume anything about any other class than
    // the current holder in a given context.
    if (enableWholeProgramOptimizations()) {
      assert appInfo().hasSubtyping();
      if (appInfo().hasSubtyping()) {
        AppInfoWithSubtyping appInfo = appInfo().withSubtyping();
        return appInfo.isUnknown(type)
            ? OptionalBool.unknown()
            : OptionalBool.of(appInfo.isMarkedAsInterface(type));
      }
    }
    return OptionalBool.unknown();
  }

  @Override
  public DexItemFactory dexItemFactory() {
    return dexItemFactory;
  }

  public boolean enableWholeProgramOptimizations() {
    return wholeProgramOptimizations == WholeProgramOptimizations.ON;
  }

  public ProtoShrinker protoShrinker() {
    return protoShrinker;
  }

  public void withGeneratedExtensionRegistryShrinker(
      Consumer<GeneratedExtensionRegistryShrinker> consumer) {
    if (protoShrinker != null && protoShrinker.generatedExtensionRegistryShrinker != null) {
      consumer.accept(protoShrinker.generatedExtensionRegistryShrinker);
    }
  }

  public <U> U withGeneratedExtensionRegistryShrinker(
      Function<GeneratedExtensionRegistryShrinker, U> fn, U defaultValue) {
    if (protoShrinker != null && protoShrinker.generatedExtensionRegistryShrinker != null) {
      return fn.apply(protoShrinker.generatedExtensionRegistryShrinker);
    }
    return defaultValue;
  }

  public void withGeneratedMessageLiteShrinker(Consumer<GeneratedMessageLiteShrinker> consumer) {
    if (protoShrinker != null && protoShrinker.generatedMessageLiteShrinker != null) {
      consumer.accept(protoShrinker.generatedMessageLiteShrinker);
    }
  }

  public GraphLense graphLense() {
    return graphLense;
  }

  /** @return true if the graph lens changed, otherwise false. */
  public boolean setGraphLense(GraphLense graphLense) {
    if (graphLense != this.graphLense) {
      this.graphLense = graphLense;
      return true;
    }
    return false;
  }

  public void setInitializedClassesInInstanceMethods(
      InitializedClassesInInstanceMethods initializedClassesInInstanceMethods) {
    this.initializedClassesInInstanceMethods = initializedClassesInInstanceMethods;
  }

  public <U> U withInitializedClassesInInstanceMethods(
      Function<InitializedClassesInInstanceMethods, U> fn, U defaultValue) {
    if (initializedClassesInInstanceMethods != null) {
      return fn.apply(initializedClassesInInstanceMethods);
    }
    return defaultValue;
  }

  public InternalOptions options() {
    return options;
  }

  public RootSet rootSet() {
    return rootSet;
  }

  public void setRootSet(RootSet rootSet) {
    assert this.rootSet == null : "Root set should never be recomputed";
    this.rootSet = rootSet;
  }

  public Set<DexMethod> unneededVisibilityBridgeMethods() {
    return unneededVisibilityBridgeMethods;
  }

  public void setUnneededVisibilityBridgeMethods(Set<DexMethod> unneededVisibilityBridgeMethods) {
    this.unneededVisibilityBridgeMethods = unneededVisibilityBridgeMethods;
  }

  // Get the result of vertical class merging. Returns null if vertical class merging has not been
  // run.
  public VerticallyMergedClasses verticallyMergedClasses() {
    return verticallyMergedClasses;
  }

  public void setVerticallyMergedClasses(VerticallyMergedClasses verticallyMergedClasses) {
    this.verticallyMergedClasses = verticallyMergedClasses;
  }

  @SuppressWarnings("unchecked")
  public AppView<AppInfoWithSubtyping> withSubtyping() {
    return appInfo.hasSubtyping()
        ? (AppView<AppInfoWithSubtyping>) this
        : null;
  }

  public AppView<AppInfoWithLiveness> withLiveness() {
    @SuppressWarnings("unchecked")
    AppView<AppInfoWithLiveness> appViewWithLiveness = (AppView<AppInfoWithLiveness>) this;
    return appViewWithLiveness;
  }

  public OptionalBool isSubtype(DexType subtype, DexType supertype) {
    return appInfo().hasSubtyping()
        ? OptionalBool.of(appInfo().withSubtyping().isSubtype(subtype, supertype))
        : OptionalBool.unknown();
  }
}
