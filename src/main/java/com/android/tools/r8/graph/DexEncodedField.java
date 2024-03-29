// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.dex.MixedSectionCollection;
import com.android.tools.r8.ir.code.ConstInstruction;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.shaking.AppInfoWithLiveness;

public class DexEncodedField extends KeyedDexItem<DexField> {
  public static final DexEncodedField[] EMPTY_ARRAY = {};

  public final DexField field;
  public final FieldAccessFlags accessFlags;
  public DexAnnotationSet annotations;
  private DexValue staticValue;

  private FieldOptimizationInfo optimizationInfo = DefaultFieldOptimizationInfo.getInstance();

  public DexEncodedField(
      DexField field,
      FieldAccessFlags accessFlags,
      DexAnnotationSet annotations,
      DexValue staticValue) {
    this.field = field;
    this.accessFlags = accessFlags;
    this.annotations = annotations;
    this.staticValue = staticValue;
  }

  public boolean isProgramField(DexDefinitionSupplier definitions) {
    if (field.holder.isClassType()) {
      DexClass clazz = definitions.definitionFor(field.holder);
      return clazz != null && clazz.isProgramClass();
    }
    return false;
  }

  public FieldOptimizationInfo getOptimizationInfo() {
    return optimizationInfo;
  }

  public synchronized MutableFieldOptimizationInfo getMutableOptimizationInfo() {
    if (optimizationInfo.isDefaultFieldOptimizationInfo()) {
      MutableFieldOptimizationInfo mutableOptimizationInfo = new MutableFieldOptimizationInfo();
      optimizationInfo = mutableOptimizationInfo;
      return mutableOptimizationInfo;
    }
    assert optimizationInfo.isMutableFieldOptimizationInfo();
    return optimizationInfo.asMutableFieldOptimizationInfo();
  }

  public void setOptimizationInfo(MutableFieldOptimizationInfo info) {
    optimizationInfo = info;
  }

  @Override
  public void collectIndexedItems(
      IndexedItemCollection indexedItems, DexMethod method, int instructionOffset) {
    field.collectIndexedItems(indexedItems, method, instructionOffset);
    annotations.collectIndexedItems(indexedItems, method, instructionOffset);
    if (accessFlags.isStatic()) {
      getStaticValue().collectIndexedItems(indexedItems, method, instructionOffset);
    }
  }

  @Override
  void collectMixedSectionItems(MixedSectionCollection mixedItems) {
    annotations.collectMixedSectionItems(mixedItems);
  }

  @Override
  public String toString() {
    return "Encoded field " + field;
  }

  @Override
  public String toSmaliString() {
    return field.toSmaliString();
  }

  @Override
  public String toSourceString() {
    return field.toSourceString();
  }

  @Override
  public DexField getKey() {
    return field;
  }

  @Override
  public DexReference toReference() {
    return field;
  }

  @Override
  public boolean isDexEncodedField() {
    return true;
  }

  @Override
  public DexEncodedField asDexEncodedField() {
    return this;
  }

  @Override
  public boolean isStatic() {
    return accessFlags.isStatic();
  }

  public boolean isPrivate() {
    return accessFlags.isPrivate();
  }

  @Override
  public boolean isStaticMember() {
    return isStatic();
  }

  public boolean hasAnnotation() {
    return !annotations.isEmpty();
  }

  public boolean hasExplicitStaticValue() {
    assert accessFlags.isStatic();
    return staticValue != null;
  }

  public void setStaticValue(DexValue staticValue) {
    assert accessFlags.isStatic();
    assert staticValue != null;
    this.staticValue = staticValue;
  }

  public DexValue getStaticValue() {
    assert accessFlags.isStatic();
    return staticValue == null ? DexValue.defaultForType(field.type) : staticValue;
  }

  /**
   * Returns a const instructions if this field is a compile time final const.
   *
   * <p>NOTE: It is the responsibility of the caller to check if this field is pinned or not.
   */
  public ConstInstruction valueAsConstInstruction(
      IRCode code, Value dest, AppView<AppInfoWithLiveness> appView) {
    // If it is a static field, we can only propagate the value if class initialization does not
    // have side effects.
    if (isStatic()) {
      DexClass clazz = appView.definitionFor(field.holder);
      if (clazz == null) {
        return null;
      }
      DexType context = code.method.method.holder;
      if (clazz.classInitializationMayHaveSideEffects(
          appView,
          // Types that are a super type of the current context are guaranteed to be initialized
          // already.
          type -> appView.isSubtype(context, type).isTrue())) {
        // Ignore class initialization side-effects for dead proto extension fields to ensure that
        // we force replace these field reads by null.
        boolean ignore =
            appView.withGeneratedExtensionRegistryShrinker(
                shrinker -> shrinker.isDeadProtoExtensionField(field), false);
        if (!ignore) {
          return null;
        }
      }
    }

    boolean isWritten = appView.appInfo().isFieldWrittenByFieldPutInstruction(this);
    if (!isWritten) {
      // Since the field is not written, we can simply return the default value for the type.
      DexValue value = isStatic() ? getStaticValue() : DexValue.defaultForType(field.type);
      return value.asConstInstruction(code, dest, appView.options());
    }

    // The only way to figure out whether the DexValue contains the final value is ensure the value
    // is not the default or check that <clinit> is not present.
    if (accessFlags.isFinal() && isStatic()) {
      DexClass clazz = appView.definitionFor(field.holder);
      if (clazz == null || clazz.hasClassInitializer()) {
        return null;
      }
      DexValue staticValue = getStaticValue();
      if (!staticValue.isDefault(field.type)) {
        return staticValue.asConstInstruction(code, dest, appView.options());
      }
    }

    return null;
  }

  public DexEncodedField toTypeSubstitutedField(DexField field) {
    if (this.field == field) {
      return this;
    }
    DexEncodedField result = new DexEncodedField(field, accessFlags, annotations, staticValue);
    result.optimizationInfo =
        optimizationInfo.isMutableFieldOptimizationInfo()
            ? optimizationInfo.asMutableFieldOptimizationInfo().mutableCopy()
            : DefaultFieldOptimizationInfo.getInstance();
    return result;
  }
}
