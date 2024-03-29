// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.array;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.NeverPropagateValue;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import com.google.common.collect.Streams;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DeadArrayLengthTest extends TestBase {
  private static final Class<?> MAIN = TestClass.class;
  private static final String EXPECTED_OUTPUT = StringUtils.lines("1", "Expected NPE", "3");

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().build();
  }

  public DeadArrayLengthTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject main = inspector.clazz(MAIN);
    assertThat(main, isPresent());

    MethodSubject nonNull = main.uniqueMethodWithName("clearlyNonNull");
    assertThat(nonNull, isPresent());
    assertEquals(
        0,
        Streams.stream(nonNull.iterateInstructions(InstructionSubject::isArrayLength)).count());

    MethodSubject nullable = main.uniqueMethodWithName("isNullable");
    assertThat(nullable, isPresent());
    assertEquals(
        1,
        Streams.stream(nullable.iterateInstructions(InstructionSubject::isArrayLength)).count());

    MethodSubject nullCheck = main.uniqueMethodWithName("afterNullCheck");
    assertThat(nullCheck, isPresent());
    // TODO(b/120920488): could be zero if we extend non-null to first dead code removal.
    assertEquals(
        1,
        Streams.stream(nullCheck.iterateInstructions(InstructionSubject::isArrayLength)).count());
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    testForD8()
        .release()
        .addProgramClasses(MAIN)
        .setMinApi(parameters.getRuntime())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutput(EXPECTED_OUTPUT)
        .inspect(this::inspect);
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addProgramClasses(MAIN)
        .addKeepClassAndMembersRules(MAIN)
        .enableMemberValuePropagationAnnotations()
        .noMinification()
        .setMinApi(parameters.getRuntime())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutput(EXPECTED_OUTPUT)
        .inspect(this::inspect);
  }

  static class TestClass {
    @NeverPropagateValue
    static int clearlyNonNull(int size) {
      int[] array = new int[size];
      int l = array.length;
      return System.currentTimeMillis() > 0 ? 1 : -1;
    }

    @NeverPropagateValue
    static int isNullable(int[] args) {
      // Not used, but can't be dead due to the potential NPE.
      int l = args.length;
      return System.currentTimeMillis() > 0 ? 2 : -2;
    }

    @NeverPropagateValue
    static int afterNullCheck(int[] args) {
      if (args != null) {
        // Can be removed.
        int l = args.length;
        return System.currentTimeMillis() > 0 ? 8 : -8;
      }
      return System.currentTimeMillis() > 0 ? 3 : -3;
    }

    public static void main(String[] args) {
      System.out.println(clearlyNonNull(args.length));
      try {
        System.out.println(isNullable(null));
        throw new AssertionError("Expect to see NPE");
      } catch (NullPointerException npe) {
        System.out.println("Expected NPE");
      }
      try {
        System.out.println(afterNullCheck(null));
      } catch (NullPointerException npe) {
        throw new AssertionError("Not expect to see NPE");
      }
    }
  }
}
