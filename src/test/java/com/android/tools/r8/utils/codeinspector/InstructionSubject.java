// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.codeinspector;

public interface InstructionSubject {

  enum JumboStringMode {
    ALLOW,
    DISALLOW
  };

  boolean isFieldAccess();

  boolean isInvokeVirtual();

  boolean isInvokeInterface();

  boolean isInvokeStatic();

  boolean isNop();

  boolean isConstString(JumboStringMode jumboStringMode);

  boolean isConstString(String value, JumboStringMode jumboStringMode);

  boolean isGoto();

  boolean isIfNez();

  boolean isIfEqz();

  boolean isReturnVoid();

  boolean isReturnObject();

  boolean isThrow();

  boolean isInvoke();

  boolean isNewInstance();

  boolean isInstancePut();

  boolean isStaticPut();

  boolean isInstanceGet();

  boolean isStaticGet();

  boolean isCheckCast();

  boolean isCheckCast(String type);

  boolean isIf(); // Also include CF/if_cmp* instructions.

  boolean isPackedSwitch();

  boolean isSparseSwitch();
}