// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.InnerClassAttribute;
import com.android.tools.r8.graph.KeyedDexItem;
import com.android.tools.r8.graph.NestMemberClassAttribute;
import com.android.tools.r8.graph.PresortedComparable;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.utils.ExceptionUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class TreePruner {

  private final DexApplication application;
  private final AppView<AppInfoWithLiveness> appView;
  private final UsagePrinter usagePrinter;
  private final Set<DexType> prunedTypes = Sets.newIdentityHashSet();
  private final Set<DexMethod> methodsToKeepForConfigurationDebugging = Sets.newIdentityHashSet();

  public TreePruner(DexApplication application, AppView<AppInfoWithLiveness> appView) {
    InternalOptions options = appView.options();
    this.application = application;
    this.appView = appView;
    this.usagePrinter =
        options.hasUsageInformationConsumer()
            ? new UsagePrinter(
                s ->
                    ExceptionUtils.withConsumeResourceHandler(
                        options.reporter, options.usageInformationConsumer, s))
            : UsagePrinter.DONT_PRINT;
  }

  public DexApplication run() {
    application.timing.begin("Pruning application...");
    DexApplication result;
    try {
      result = removeUnused(application).build();
    } finally {
      application.timing.end();
    }
    return result;
  }

  private DexApplication.Builder<?> removeUnused(DexApplication application) {
    return application.builder()
        .replaceProgramClasses(getNewProgramClasses(application.classes()));
  }

  private List<DexProgramClass> getNewProgramClasses(List<DexProgramClass> classes) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    InternalOptions options = appView.options();
    List<DexProgramClass> newClasses = new ArrayList<>();
    for (DexProgramClass clazz : classes) {
      if (options.configurationDebugging) {
        newClasses.add(clazz);
        pruneMembersAndAttributes(clazz);
        continue;
      }
      if (!appInfo.liveTypes.contains(clazz.type)) {
        // The class is completely unused and we can remove it.
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing class: " + clazz);
        }
        prunedTypes.add(clazz.type);
        usagePrinter.printUnusedClass(clazz);
      } else {
        newClasses.add(clazz);
        if (!appInfo.instantiatedTypes.contains(clazz.type)
            && !options.forceProguardCompatibility) {
          // The class is only needed as a type but never instantiated. Make it abstract to reflect
          // this.
          if (clazz.accessFlags.isFinal()) {
            // We cannot mark this class abstract, as it is final (not supported on Android).
            // However, this might extend an abstract class and we might have removed the
            // corresponding methods in this class. This might happen if we only keep this
            // class around for its constants.
            // For now, we remove the final flag to still be able to mark it abstract.
            clazz.accessFlags.demoteFromFinal();
          }
          clazz.accessFlags.setAbstract();
        }
        // The class is used and must be kept. Remove the unused fields and methods from the class.
        pruneUnusedInterfaces(clazz);
        pruneMembersAndAttributes(clazz);
      }
    }
    return newClasses;
  }

  private void pruneUnusedInterfaces(DexProgramClass clazz) {
    int numberOfReachableInterfaces = 0;
    for (DexType type : clazz.interfaces.values) {
      if (appView.appInfo().liveTypes.contains(type)) {
        numberOfReachableInterfaces++;
      }
    }

    if (numberOfReachableInterfaces == clazz.interfaces.size()) {
      return;
    }

    if (numberOfReachableInterfaces == 0) {
      clazz.interfaces = DexTypeList.empty();
      return;
    }

    DexType[] reachableInterfaces = new DexType[numberOfReachableInterfaces];
    int i = 0;
    for (DexType type : clazz.interfaces.values) {
      if (appView.appInfo().liveTypes.contains(type)) {
        reachableInterfaces[i] = type;
        i++;
      }
    }
    clazz.interfaces = new DexTypeList(reachableInterfaces);
  }

  private void pruneMembersAndAttributes(DexProgramClass clazz) {
    usagePrinter.visiting(clazz);
    DexEncodedMethod[] reachableDirectMethods = reachableMethods(clazz.directMethods(), clazz);
    if (reachableDirectMethods != null) {
      clazz.setDirectMethods(reachableDirectMethods);
    }
    DexEncodedMethod[] reachableVirtualMethods =
        reachableMethods(clazz.virtualMethods(), clazz);
    if (reachableVirtualMethods != null) {
      clazz.setVirtualMethods(reachableVirtualMethods);
    }
    DexEncodedField[] reachableInstanceFields = reachableFields(clazz.instanceFields());
    if (reachableInstanceFields != null) {
      clazz.setInstanceFields(reachableInstanceFields);
    }
    DexEncodedField[] reachableStaticFields = reachableFields(clazz.staticFields());
    if (reachableStaticFields != null) {
      clazz.setStaticFields(reachableStaticFields);
    }
    clazz.removeInnerClasses(this::isAttributeReferencingPrunedType);
    clazz.removeEnclosingMethod(this::isAttributeReferencingPrunedItem);
    rewriteNestAttributes(clazz);
    usagePrinter.visited();
  }

  private void rewriteNestAttributes(DexProgramClass clazz) {
    if (!clazz.isInANest() || !isTypeLive(clazz.type)) {
      return;
    }
    if (clazz.isNestHost()) {
      clearDeadNestMembers(clazz);
    } else {
      assert clazz.isNestMember();
      if (!isTypeLive(clazz.getNestHost())) {
        claimNestOwnership(clazz);
      }
    }
  }

  private boolean isTypeLive(DexType type) {
    return appView.appInfo().liveTypes.contains(type);
  }

  private void clearDeadNestMembers(DexClass nestHost) {
    // null definition should raise a warning which is raised later on in Nest specific passes.
    nestHost
        .getNestMembersClassAttributes()
        .removeIf(
            nestMemberAttr ->
                appView.definitionFor(nestMemberAttr.getNestMember()) != null
                    && !isTypeLive(nestMemberAttr.getNestMember()));
  }

  private void claimNestOwnership(DexClass newHost) {
    DexClass previousHost = appView.definitionFor(newHost.getNestHost());
    if (previousHost == null) {
      // Nest host will be cleared from all nest members in Nest specific passes.
      return;
    }
    newHost.clearNestHost();
    for (NestMemberClassAttribute attr : previousHost.getNestMembersClassAttributes()) {
      if (attr.getNestMember() != newHost.type && isTypeLive(attr.getNestMember())) {
        DexClass nestMember = appView.definitionFor(attr.getNestMember());
        if (nestMember != null) {
          nestMember.setNestHost(newHost.type);
        }
        // We still need to add it, even if the definition is null,
        // so the warning / error are correctly raised in Nest specific passes.
        newHost
            .getNestMembersClassAttributes()
            .add(new NestMemberClassAttribute(attr.getNestMember()));
      }
    }
  }

  private boolean isAttributeReferencingPrunedItem(EnclosingMethodAttribute attr) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    return
        (attr.getEnclosingClass() != null
            && !appInfo.liveTypes.contains(attr.getEnclosingClass()))
        || (attr.getEnclosingMethod() != null
            && !appInfo.liveMethods.contains(attr.getEnclosingMethod()));
  }

  private boolean isAttributeReferencingPrunedType(InnerClassAttribute attr) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    if (!appInfo.liveTypes.contains(attr.getInner())) {
      return true;
    }
    DexType context = attr.getLiveContext(appInfo);
    return context == null || !appInfo.liveTypes.contains(context);
  }

  private <S extends PresortedComparable<S>, T extends KeyedDexItem<S>> int firstUnreachableIndex(
      List<T> items, Predicate<T> live) {
    for (int i = 0; i < items.size(); i++) {
      if (!live.test(items.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private DexEncodedMethod[] reachableMethods(List<DexEncodedMethod> methods, DexClass clazz) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    InternalOptions options = appView.options();
    int firstUnreachable =
        firstUnreachableIndex(methods, method -> appInfo.liveMethods.contains(method.method));
    // Return the original array if all methods are used.
    if (firstUnreachable == -1) {
      return null;
    }
    ArrayList<DexEncodedMethod> reachableMethods = new ArrayList<>(methods.size());
    for (int i = 0; i < firstUnreachable; i++) {
      reachableMethods.add(methods.get(i));
    }
    for (int i = firstUnreachable; i < methods.size(); i++) {
      DexEncodedMethod method = methods.get(i);
      if (appInfo.liveMethods.contains(method.getKey())) {
        reachableMethods.add(method);
      } else if (options.configurationDebugging) {
        // Keep the method but rewrite its body, if it has one.
        reachableMethods.add(
            method.shouldNotHaveCode() && !method.hasCode()
                ? method
                : method.toMethodThatLogsError(appView));
        methodsToKeepForConfigurationDebugging.add(method.method);
      } else if (appInfo.targetedMethods.contains(method.getKey())) {
        // If the method is already abstract, and doesn't have code, let it be.
        if (method.shouldNotHaveCode() && !method.hasCode()) {
          reachableMethods.add(method);
          continue;
        }
        if (Log.ENABLED) {
          Log.debug(getClass(), "Making method %s abstract.", method.method);
        }
        // Final classes cannot be abstract, so we have to keep the method in that case.
        // Also some other kinds of methods cannot be abstract, so keep them around.
        boolean allowAbstract = clazz.accessFlags.isAbstract()
            && !method.accessFlags.isFinal()
            && !method.accessFlags.isNative()
            && !method.accessFlags.isStrict()
            && !method.accessFlags.isSynchronized()
            && !method.accessFlags.isPrivate();
        // By construction, static methods cannot be reachable but non-live. For private methods
        // this can only happen as the result of an invalid invoke. They will not actually be
        // called at runtime but we have to keep them as non-abstract (see above) to produce the
        // same failure mode.
        reachableMethods.add(
            allowAbstract
                ? method.toAbstractMethod()
                : (options.isGeneratingClassFiles()
                    ? method.toEmptyThrowingMethodCf()
                    : method.toEmptyThrowingMethodDex()));
      } else {
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing method %s.", method.method);
        }
        usagePrinter.printUnusedMethod(method);
      }
    }
    return reachableMethods.isEmpty()
        ? DexEncodedMethod.EMPTY_ARRAY
        : reachableMethods.toArray(DexEncodedMethod.EMPTY_ARRAY);
  }

  private DexEncodedField[] reachableFields(List<DexEncodedField> fields) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    Predicate<DexEncodedField> isReachableOrReferencedField =
        field -> appInfo.isFieldRead(field) || appInfo.isFieldWritten(field);
    int firstUnreachable = firstUnreachableIndex(fields, isReachableOrReferencedField);
    // Return the original array if all fields are used.
    if (firstUnreachable == -1) {
      return null;
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Removing field %s.", fields.get(firstUnreachable));
    }
    usagePrinter.printUnusedField(fields.get(firstUnreachable));
    ArrayList<DexEncodedField> reachableOrReferencedFields = new ArrayList<>(fields.size());
    for (int i = 0; i < firstUnreachable; i++) {
      reachableOrReferencedFields.add(fields.get(i));
    }
    for (int i = firstUnreachable + 1; i < fields.size(); i++) {
      DexEncodedField field = fields.get(i);
      if (isReachableOrReferencedField.test(field)) {
        reachableOrReferencedFields.add(field);
      } else {
        if (Log.ENABLED) {
          Log.debug(getClass(), "Removing field %s.", field.field);
        }
        usagePrinter.printUnusedField(field);
      }
    }
    return reachableOrReferencedFields.isEmpty()
        ? DexEncodedField.EMPTY_ARRAY
        : reachableOrReferencedFields.toArray(DexEncodedField.EMPTY_ARRAY);
  }

  public Collection<DexType> getRemovedClasses() {
    return Collections.unmodifiableCollection(prunedTypes);
  }

  public Collection<DexReference> getMethodsToKeepForConfigurationDebugging() {
    return Collections.unmodifiableCollection(methodsToKeepForConfigurationDebugging);
  }
}
