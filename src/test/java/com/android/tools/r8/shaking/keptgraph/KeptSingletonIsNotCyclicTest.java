// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking.keptgraph;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersBuilder;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.shaking.WhyAreYouKeepingConsumer;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.graphinspector.GraphInspector;
import com.android.tools.r8.utils.graphinspector.GraphInspector.QueryNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class KeptSingletonIsNotCyclicTest extends TestBase {

  private static final String EXPECTED = StringUtils.lines("Foo!");

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return TestParametersBuilder.builder().withCfRuntimes().build();
  }

  public KeptSingletonIsNotCyclicTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testStaticMethod() throws Exception {
    test(FooStaticMethod.class, TestStaticMethod.class);
  }

  @Test
  public void testStaticField() throws Exception {
    test(FooStaticField.class, TestStaticField.class);
  }

  private void test(Class<?> fooClass, Class<?> testClass) throws Exception {
    WhyAreYouKeepingConsumer whyAreYouKeepingConsumer = new WhyAreYouKeepingConsumer(null);
    GraphInspector inspector =
        testForR8(parameters.getBackend())
            .enableClassInliningAnnotations()
            .enableGraphInspector(whyAreYouKeepingConsumer)
            .addProgramClasses(testClass, fooClass)
            .addKeepMainRule(testClass)
            .run(parameters.getRuntime(), testClass)
            .assertSuccessWithOutput(EXPECTED)
            .graphInspector();

    ClassReference fooClassRef = Reference.classFromClass(fooClass);
    MethodReference fooClInitRef = Reference.classConstructor(fooClassRef);
    MethodReference fooInitRef = Reference.methodFromMethod(fooClass.getDeclaredConstructor());

    MethodReference testInitRef =
        Reference.methodFromMethod(testClass.getDeclaredConstructor());

    MethodReference mainMethodRef =
        Reference.methodFromMethod(testClass.getDeclaredMethod("main", String[].class));

    // Check that whyareyoukeeping does not mention cycles.
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    whyAreYouKeepingConsumer.printWhyAreYouKeeping(fooClassRef, new PrintStream(baos));
    assertThat(
        baos.toString().replace(getClass().getTypeName(), "<test>").toLowerCase(),
        not(anyOf(containsString("cyclic"), containsString("cycle"))));

    // The only root should be the keep main-method rule.
    assertEquals(1, inspector.getRoots().size());
    QueryNode root = inspector.rule(Origin.unknown(), 1, 1).assertRoot();

    // TestClass.main is kept by the root rule.
    QueryNode mainMethod = inspector.method(mainMethodRef).assertNotRenamed().assertKeptBy(root);
    // TestClass.<init> is kept by TestClass.main.
    QueryNode testInit = inspector.method(testInitRef).assertPresent().assertKeptBy(mainMethod);
    // Foo.<clinit> is kept by TestClass.<init>
    QueryNode fooClInit = inspector.method(fooClInitRef).assertPresent().assertKeptBy(testInit);
    // Foo.<init> is kept by Foo.<clinit>
    QueryNode fooInit = inspector.method(fooInitRef).assertPresent().assertKeptBy(fooClInit);
    // TODO(b/139273002): It should be the class constructor of Foo that mark Foo as instantiated.
    inspector.clazz(fooClassRef).assertRenamed().assertKeptBy(fooClInit);
    assertFalse(inspector.clazz(fooClassRef).assertRenamed().isKeptBy(fooInit));
  }

  public static final class FooStaticMethod {

    static final FooStaticMethod INSTANCE = new FooStaticMethod();

    public static FooStaticMethod getInstance() {
      return INSTANCE;
    }

    private FooStaticMethod() {}

    @Override
    public String toString() {
      return "Foo!";
    }
  }

  @NeverClassInline
  public static class TestStaticMethod {
    public FooStaticMethod foo;

    public TestStaticMethod() {
      this.foo = FooStaticMethod.getInstance();
    }

    public static void main(String[] args) {
      System.out.println(new TestStaticMethod().foo.toString());
    }
  }

  public static final class FooStaticField {

    static final FooStaticField INSTANCE = new FooStaticField();

    private FooStaticField() {}

    @Override
    public String toString() {
      return "Foo!";
    }
  }

  @NeverClassInline
  public static class TestStaticField {
    public FooStaticField foo;

    public TestStaticField() {
      this.foo = FooStaticField.INSTANCE;
    }

    public static void main(String[] args) {
      System.out.println(new TestStaticField().foo.toString());
    }
  }
}
