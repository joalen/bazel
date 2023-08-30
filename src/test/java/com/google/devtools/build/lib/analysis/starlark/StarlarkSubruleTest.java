// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.starlark.util.BazelEvaluationTestCase;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkSubruleApi;
import net.starlark.java.eval.BuiltinFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class StarlarkSubruleTest extends BuildViewTestCase {

  private final BazelEvaluationTestCase ev = new BazelEvaluationTestCase("//subrule_testing:label");
  private final BazelEvaluationTestCase evOutsideAllowlist =
      new BazelEvaluationTestCase("//foo:bar");

  @Test
  public void testSubruleFunctionSymbol_notVisibleInBUILD() throws Exception {
    scratch.file("foo/BUILD", "subrule");

    checkLoadingPhaseError("//foo", "'subrule' is not defined");
  }

  @Test
  // checks that 'subrule' symbol visibility in bzl files, not whether it's callable
  public void testSubruleFunctionSymbol_isVisibleInBzl() throws Exception {
    Object subruleFunction = ev.eval("subrule");

    assertNoEvents();
    assertThat(subruleFunction).isNotNull();
    assertThat(subruleFunction).isInstanceOf(BuiltinFunction.class);
  }

  @Test
  public void testSubruleInstantiation_inAllowlistedPackage_succeeds() throws Exception {
    Object subrule = ev.eval("subrule(implementation = lambda : 0 )");

    assertThat(subrule).isNotNull();
    assertThat(subrule).isInstanceOf(StarlarkSubruleApi.class);
  }

  @Test
  public void testSubrule_isCallableOnlyFromRuleOrAspectImplementation() throws Exception {
    ev.exec("x = subrule(implementation = lambda : 'dummy result' )");

    ev.checkEvalErrorContains(
        "subrule(lambda) can only be called from a rule or aspect implementation", "x()");
  }

  @Test
  public void testSubrule_isCallableFromRule() throws Exception {
    scratch.file(
        "subrule_testing/myrule.bzl",
        "_my_subrule = subrule(implementation = lambda : 'dummy rule result')",
        "",
        "MyInfo = provider()",
        "def _rule_impl(ctx):",
        "  res = _my_subrule()",
        "  return MyInfo(result = res)",
        "",
        "my_rule = rule(implementation = _rule_impl)");
    scratch.file(
        "subrule_testing/BUILD",
        //
        "load('myrule.bzl', 'my_rule')",
        "my_rule(name = 'foo')");

    StructImpl provider =
        getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo");

    assertThat(provider).isNotNull();
    assertThat(provider.getValue("result")).isEqualTo("dummy rule result");
  }

  @Test
  public void testSubrule_isCallableFromAspect() throws Exception {
    scratch.file(
        "subrule_testing/myrule.bzl",
        "_my_subrule = subrule(implementation = lambda : 'dummy aspect result')",
        "",
        "MyInfo = provider()",
        "def _aspect_impl(ctx,target):",
        "  res = _my_subrule()",
        "  return MyInfo(result = res)",
        "",
        "_my_aspect = aspect(implementation = _aspect_impl)",
        "",
        "my_rule = rule(",
        "  implementation = lambda ctx: [ctx.attr.dep[MyInfo]],",
        "  attrs = {'dep' : attr.label(mandatory = True, aspects = [_my_aspect])},",
        ")");
    scratch.file(
        "subrule_testing/BUILD",
        //
        "load('myrule.bzl', 'my_rule')",
        "java_library(name = 'bar')",
        "my_rule(name = 'foo', dep = 'bar')");

    StructImpl provider =
        getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo");

    assertThat(provider).isNotNull();
    assertThat(provider.getValue("result")).isEqualTo("dummy aspect result");
  }

  @Test
  public void testSubruleInstantiation_outsideAllowlist_failsWithPrivateAPIError()
      throws Exception {
    evOutsideAllowlist.checkEvalErrorContains(
        "'//foo:bar' cannot use private API", "subrule(implementation = lambda: 0 )");
  }

  private StructImpl getProvider(String targetLabel, String providerLabel, String providerName)
      throws LabelSyntaxException {
    ConfiguredTarget target = getConfiguredTarget(targetLabel);
    Provider.Key key = new StarlarkProvider.Key(Label.parseCanonical(providerLabel), providerName);
    return (StructImpl) target.get(key);
  }
}