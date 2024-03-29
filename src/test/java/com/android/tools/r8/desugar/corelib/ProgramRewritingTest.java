// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.corelib;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertFalse;

import com.android.tools.r8.D8TestRunResult;
import com.android.tools.r8.R8TestRunResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.DexVm;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ProgramRewritingTest extends CoreLibDesugarTestBase {

  private static final String TEST_CLASS = "stream.ProgramRewritingTestClass";

  private final TestParameters parameters;
  private final boolean shrinkCoreLibrary;

  @Parameters(name = "{1}, shrinkCoreLibrary: {0}")
  public static List<Object[]> data() {
    return buildParameters(
        BooleanUtils.values(), getTestParameters().withDexRuntimes().withAllApiLevels().build());
  }

  public ProgramRewritingTest(boolean shrinkDesugaredLibrary, TestParameters parameters) {
    this.shrinkCoreLibrary = shrinkDesugaredLibrary;
    this.parameters = parameters;
  }

  @Test
  public void testProgramD8() throws Exception {
    Assume.assumeTrue("No desugaring for high API levels", requiresCoreLibDesugaring(parameters));
    KeepRuleConsumer keepRuleConsumer = createKeepRuleConsumer(parameters);
    D8TestRunResult d8TestRunResult =
        testForD8()
            .addProgramFiles(Paths.get(ToolHelper.EXAMPLES_JAVA9_BUILD_DIR + "stream.jar"))
            .setMinApi(parameters.getApiLevel())
            .enableCoreLibraryDesugaring(parameters.getApiLevel(), keepRuleConsumer)
            .compile()
            .inspect(this::checkRewrittenInvokes)
            .addRunClasspathFiles(
                buildDesugaredLibrary(
                    parameters.getApiLevel(), keepRuleConsumer.get(), shrinkCoreLibrary))
            .run(parameters.getRuntime(), TEST_CLASS)
            .assertSuccess();
    assertLines2By2Correct(d8TestRunResult.getStdOut());
    assertGeneratedKeepRulesAreCorrect(keepRuleConsumer.get());
    String stdErr = d8TestRunResult.getStdErr();
    if (parameters.getRuntime().asDex().getVm().isOlderThanOrEqual(DexVm.ART_4_4_4_HOST)) {
      // Flaky: There might be a missing method on lambda deserialization.
      assertTrue(
          !stdErr.contains("Could not find method")
              || stdErr.contains("Could not find method java.lang.invoke.SerializedLambda"));
    } else {
      assertFalse(stdErr.contains("Could not find method"));
    }
  }

  @Test
  public void testProgramR8() throws Exception {
    Assume.assumeTrue("No desugaring for high API levels", requiresCoreLibDesugaring(parameters));
    for (Boolean minifying : BooleanUtils.values()) {
      KeepRuleConsumer keepRuleConsumer = createKeepRuleConsumer(parameters);
      R8TestRunResult r8TestRunResult =
          testForR8(parameters.getBackend())
              .minification(minifying)
              .addKeepMainRule(TEST_CLASS)
              .addProgramFiles(Paths.get(ToolHelper.EXAMPLES_JAVA9_BUILD_DIR + "stream.jar"))
              .setMinApi(parameters.getApiLevel())
              .enableCoreLibraryDesugaring(parameters.getApiLevel(), keepRuleConsumer)
              .compile()
              .inspect(this::checkRewrittenInvokes)
              .addRunClasspathFiles(
                  buildDesugaredLibrary(
                      parameters.getApiLevel(), keepRuleConsumer.get(), shrinkCoreLibrary))
              .run(parameters.getRuntime(), TEST_CLASS)
              .assertSuccess();
      assertLines2By2Correct(r8TestRunResult.getStdOut());
      assertGeneratedKeepRulesAreCorrect(keepRuleConsumer.get());
      if (parameters.getRuntime().asDex().getVm().isOlderThanOrEqual(DexVm.ART_4_4_4_HOST)) {
        // Flaky: There might be a missing method on lambda deserialization.
        r8TestRunResult.assertStderrMatches(
            anyOf(
                not(containsString("Could not find method")),
                containsString("Could not find method java.lang.invoke.SerializedLambda")));
      } else {
        r8TestRunResult.assertStderrMatches(not(containsString("Could not find method")));
      }
    }
  }

  private void checkRewrittenInvokes(CodeInspector inspector) {
    if (!requiresCoreLibDesugaring(parameters)) {
      return;
    }
    ClassSubject classSubject = inspector.clazz(TEST_CLASS);
    assertThat(classSubject, isPresent());
    List<InstructionSubject> invokes =
        classSubject
            .uniqueMethodWithName("main")
            .streamInstructions()
            .filter(instr -> instr.isInvokeInterface() || instr.isInvokeStatic())
            .collect(Collectors.toList());
    assertEquals(23, invokes.size());
    assertInvokeStaticMatching(invokes, 0, "Set$-EL;->spliterator");
    assertInvokeStaticMatching(invokes, 1, "List$-EL;->spliterator");
    assertInvokeStaticMatching(invokes, 2, "Collection$-EL;->stream");
    assertInvokeInterfaceMatching(invokes, 3, "Set;->iterator");
    assertInvokeStaticMatching(invokes, 4, "Collection$-EL;->stream");
    assertInvokeStaticMatching(invokes, 5, "DesugarLinkedHashSet;->spliterator");
    assertInvokeInterfaceMatching(invokes, 9, "Iterator;->remove");
    assertInvokeStaticMatching(invokes, 10, "DesugarArrays;->spliterator");
    assertInvokeStaticMatching(invokes, 11, "DesugarArrays;->spliterator");
    assertInvokeStaticMatching(invokes, 12, "DesugarArrays;->stream");
    assertInvokeStaticMatching(invokes, 13, "DesugarArrays;->stream");
    assertInvokeStaticMatching(invokes, 14, "Collection$-EL;->stream");
    assertInvokeStaticMatching(invokes, 15, "IntStream$-CC;->range");
    assertInvokeStaticMatching(invokes, 17, "Comparator$-CC;->comparingInt");
    assertInvokeStaticMatching(invokes, 18, "List$-EL;->sort");
    assertInvokeStaticMatching(invokes, 20, "Comparator$-CC;->comparingInt");
    assertInvokeStaticMatching(invokes, 21, "List$-EL;->sort");
    assertInvokeStaticMatching(invokes, 22, "Collection$-EL;->stream");
    // TODO (b/134732760): Support Java 9 Stream APIs
    // assertTrue(invokes.get(17).isInvokeStatic());
    // assertTrue(invokes.get(17).toString().contains("Stream$-CC;->iterate"));
  }

  private void assertInvokeInterfaceMatching(List<InstructionSubject> invokes, int i, String s) {
    assertTrue(invokes.get(i).isInvokeInterface());
    assertTrue(invokes.get(i).toString().contains(s));
  }

  private void assertInvokeStaticMatching(List<InstructionSubject> invokes, int i, String s) {
    assertTrue(invokes.get(i).isInvokeStatic());
    assertTrue(invokes.get(i).toString().contains(s));
  }

  private void assertGeneratedKeepRulesAreCorrect(String keepRules) {
    String expectedResult =
        StringUtils.lines(
            "-keep class j$.util.List$-EL {",
            "    void sort(java.util.List, java.util.Comparator);",
            "    j$.util.Spliterator spliterator(java.util.List);",
            "}",
            "-keep class j$.util.Collection$-EL {",
            "    j$.util.stream.Stream stream(java.util.Collection);",
            "}",
            "-keep class j$.util.stream.IntStream$-CC {",
            "    j$.util.stream.IntStream range(int, int);",
            "}",
            "-keep class j$.util.Comparator$-CC {",
            "    java.util.Comparator comparingInt(j$.util.function.ToIntFunction);",
            "}",
            "-keep class j$.util.Set$-EL {",
            "    j$.util.Spliterator spliterator(java.util.Set);",
            "}",
            "-keep class j$.util.DesugarArrays {",
            "    j$.util.Spliterator spliterator(java.lang.Object[]);",
            "    j$.util.stream.Stream stream(java.lang.Object[], int, int);",
            "    j$.util.stream.Stream stream(java.lang.Object[]);",
            "    j$.util.Spliterator spliterator(java.lang.Object[], int, int);",
            "}",
            "-keep class j$.util.stream.IntStream",
            "-keep class j$.util.DesugarLinkedHashSet {",
            "    j$.util.Spliterator spliterator(java.util.LinkedHashSet);",
            "}",
            "-keep class j$.util.stream.Stream",
            "-keep class j$.util.Spliterator",
            "-keep class j$.util.function.ToIntFunction");
    assertEquals(expectedResult, keepRules);
  }
}
