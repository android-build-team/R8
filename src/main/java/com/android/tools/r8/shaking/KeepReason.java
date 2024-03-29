// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo;
import com.android.tools.r8.experimental.graphinfo.GraphEdgeInfo.EdgeKind;
import com.android.tools.r8.experimental.graphinfo.GraphNode;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

// TODO(herhut): Canonicalize reason objects.
public abstract class KeepReason {

  public abstract GraphEdgeInfo.EdgeKind edgeKind();

  public abstract GraphNode getSourceNode(Enqueuer enqueuer);

  static KeepReason annotatedOn(DexDefinition definition) {
    return new AnnotatedOn(definition);
  }

  static KeepReason dueToKeepRule(ProguardKeepRuleBase rule) {
    if (rule instanceof ProguardKeepRule) {
      return new DueToKeepRule(rule);
    }
    if (rule instanceof ProguardIfRule) {
      ProguardIfRule ifRule = (ProguardIfRule) rule;
      return new DueToConditionalKeepRule(ifRule, ifRule.getPreconditions());
    }
    throw new Unreachable("Unexpected proguard keep rule: " + rule);
  }

  static KeepReason dueToConditionalKeepRule(ProguardKeepRuleBase rule, DexReference reference) {
    return new DueToConditionalKeepRule(rule, reference);
  }

  static KeepReason dueToProguardCompatibilityKeepRule(ProguardKeepRule rule) {
    return new DueToProguardCompatibilityKeepRule(rule);
  }

  static KeepReason instantiatedIn(DexEncodedMethod method) {
    return new InstatiatedIn(method);
  }

  public static KeepReason invokedViaSuperFrom(DexEncodedMethod from) {
    return new InvokedViaSuper(from);
  }

  public static KeepReason reachableFromLiveType(DexType type) {
    return new ReachableFromLiveType(type);
  }

  public static KeepReason invokedFrom(DexEncodedMethod method) {
    return new InvokedFrom(method);
  }

  public static KeepReason invokedFromLambdaCreatedIn(DexEncodedMethod method) {
    return new InvokedFromLambdaCreatedIn(method);
  }

  public static KeepReason isLibraryMethod(DexType implementer, DexType libraryType) {
    return new IsLibraryMethod(implementer, libraryType);
  }

  public static KeepReason fieldReferencedIn(DexEncodedMethod method) {
    return new ReferencedFrom(method);
  }

  public static KeepReason referencedInAnnotation(DexItem holder) {
    return new ReferencedInAnnotation(holder);
  }

  public boolean isDueToKeepRule() {
    return false;
  }

  public boolean isDueToReflectiveUse() {
    return false;
  }

  public boolean isDueToProguardCompatibility() {
    return false;
  }

  public boolean isDueToConditionalKeepRule() {
    return false;
  }

  public boolean isInstantiatedIn() {
    return false;
  }

  public InstatiatedIn asInstantiatedIn() {
    return null;
  }

  public ProguardKeepRuleBase getProguardKeepRule() {
    return null;
  }

  public static KeepReason targetedBySuperFrom(DexEncodedMethod from) {
    return new TargetedBySuper(from);
  }

  public static KeepReason reflectiveUseIn(DexEncodedMethod method) {
    return new ReflectiveUseFrom(method);
  }

  public static KeepReason methodHandleReferencedIn(DexEncodedMethod method) {
    return new MethodHandleReferencedFrom(method);
  }

  public static KeepReason overridesMethod(DexEncodedMethod method) {
    return new OverridesMethod(method);
  }

  public Collection<DexReference> getPreconditions() {
    throw new Unreachable();
  }

  private static class DueToKeepRule extends KeepReason {

    final ProguardKeepRuleBase keepRule;

    private DueToKeepRule(ProguardKeepRuleBase keepRule) {
      this.keepRule = keepRule;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.KeepRule;
    }

    @Override
    public boolean isDueToKeepRule() {
      return true;
    }

    @Override
    public ProguardKeepRuleBase getProguardKeepRule() {
      return keepRule;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      return enqueuer.getKeepRuleGraphNode(keepRule);
    }
  }

  private static class DueToProguardCompatibilityKeepRule extends DueToKeepRule {
    private DueToProguardCompatibilityKeepRule(ProguardKeepRule keepRule) {
      super(keepRule);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.CompatibilityRule;
    }

    @Override
    public boolean isDueToProguardCompatibility() {
      return true;
    }
  }

  private static class DueToConditionalKeepRule extends DueToKeepRule {

    private final Set<DexReference> preconditions;

    public DueToConditionalKeepRule(ProguardKeepRuleBase rule, DexReference precondition) {
      this(rule, Collections.singleton(precondition));
      assert precondition != null;
    }

    public DueToConditionalKeepRule(ProguardKeepRuleBase rule, Set<DexReference> preconditions) {
      super(rule);
      assert !preconditions.isEmpty();
      this.preconditions = preconditions;
    }

    @Override
    public Set<DexReference> getPreconditions() {
      return preconditions;
    }

    @Override
    public boolean isDueToConditionalKeepRule() {
      return true;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.ConditionalKeepRule;
    }
  }

  private abstract static class BasedOnOtherMethod extends KeepReason {

    private final DexEncodedMethod method;

    private BasedOnOtherMethod(DexEncodedMethod method) {
      this.method = method;
    }

    abstract String getKind();

    public DexMethod getMethod() {
      return method.method;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      return enqueuer.getMethodGraphNode(method.method);
    }
  }

  private static class OverridesMethod extends BasedOnOtherMethod {

    public OverridesMethod(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.OverridingMethod;
    }

    @Override
    String getKind() {
      return "overrides";
    }
  }

  public static class InstatiatedIn extends BasedOnOtherMethod {

    private InstatiatedIn(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public boolean isInstantiatedIn() {
      return true;
    }

    @Override
    public InstatiatedIn asInstantiatedIn() {
      return this;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.InstantiatedIn;
    }

    @Override
    String getKind() {
      return "instantiated in";
    }
  }

  private static class InvokedViaSuper extends BasedOnOtherMethod {

    private InvokedViaSuper(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.InvokedViaSuper;
    }

    @Override
    String getKind() {
      return "invoked via super from";
    }
  }

  private static class TargetedBySuper extends BasedOnOtherMethod {

    private TargetedBySuper(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.TargetedBySuper;
    }

    @Override
    String getKind() {
      return "targeted by super from";
    }
  }

  private static class InvokedFrom extends BasedOnOtherMethod {

    private InvokedFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.InvokedFrom;
    }

    @Override
    String getKind() {
      return "invoked from";
    }
  }

  private static class InvokedFromLambdaCreatedIn extends BasedOnOtherMethod {

    private InvokedFromLambdaCreatedIn(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.InvokedFromLambdaCreatedIn;
    }

    @Override
    String getKind() {
      return "invoked from lambda created in";
    }
  }

  private static class ReferencedFrom extends BasedOnOtherMethod {

    private ReferencedFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.ReferencedFrom;
    }

    @Override
    String getKind() {
      return "referenced from";
    }
  }

  private static class ReachableFromLiveType extends KeepReason {

    private final DexType type;

    private ReachableFromLiveType(DexType type) {
      this.type = type;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.ReachableFromLiveType;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      return enqueuer.getClassGraphNode(type);
    }
  }

  public static class IsLibraryMethod extends KeepReason {

    private final DexType implementer;
    private final DexType libraryType;

    private IsLibraryMethod(DexType implementer, DexType libraryType) {
      this.implementer = implementer;
      this.libraryType = libraryType;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.IsLibraryMethod;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      return enqueuer.getClassGraphNode(implementer);
    }
  }

  private static class ReferencedInAnnotation extends KeepReason {

    private final DexItem holder;

    private ReferencedInAnnotation(DexItem holder) {
      this.holder = holder;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.ReferencedInAnnotation;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      return enqueuer.getAnnotationGraphNode(holder);
    }
  }

  private static class AnnotatedOn extends KeepReason {

    private final DexDefinition holder;

    private AnnotatedOn(DexDefinition holder) {
      this.holder = holder;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.AnnotatedOn;
    }

    @Override
    public GraphNode getSourceNode(Enqueuer enqueuer) {
      if (holder.isDexClass()) {
        return enqueuer.getClassGraphNode(holder.asDexClass().type);
      } else if (holder.isDexEncodedField()) {
        return enqueuer.getFieldGraphNode(holder.asDexEncodedField().field);
      } else {
        assert holder.isDexEncodedMethod();
        return enqueuer.getMethodGraphNode(holder.asDexEncodedMethod().method);
      }
    }
  }

  private static class ReflectiveUseFrom extends BasedOnOtherMethod {

    private ReflectiveUseFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public boolean isDueToReflectiveUse() {
      return true;
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.ReflectiveUseFrom;
    }

    @Override
    String getKind() {
      return "reflective use in";
    }
  }

  private static class MethodHandleReferencedFrom extends BasedOnOtherMethod {

    private MethodHandleReferencedFrom(DexEncodedMethod method) {
      super(method);
    }

    @Override
    public EdgeKind edgeKind() {
      return EdgeKind.MethodHandleUseFrom;
    }

    @Override
    String getKind() {
      return "method handle referenced from";
    }
  }
}
