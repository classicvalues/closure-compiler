/*
 * Copyright 2009 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.javascript.jscomp.graph.GraphvizGraph;
import com.google.javascript.jscomp.graph.LinkedDirectedGraph;
import java.util.List;

/** Pass factories and meta-data for native Compiler passes. */
public abstract class PassConfig {

  // Used by the subclasses.
  protected final CompilerOptions options;

  public PassConfig(CompilerOptions options) {
    this.options = options;
  }

  /**
   * Gets additional checking passes that are run always, even in "whitespace only" mode. For very
   * specific cases where processing is required even in a mode which is intended not to have any
   * processing - specifically introduced to support goog.module() usage.
   */
  protected List<PassFactory> getWhitespaceOnlyPasses() {
    return ImmutableList.of();
  }

  /** Gets the transpilation passes */
  protected List<PassFactory> getTranspileOnlyPasses() {
    return ImmutableList.of();
  }

  /**
   * Gets the checking passes to run.
   *
   * <p>Checking passes revolve around emitting warnings and errors. They also may include
   * pre-processor passes needed to do error analysis more effectively.
   *
   * <p>Clients that only want to analyze code (like IDEs) and not emit code will only run checks
   * and not optimizations.
   */
  protected abstract List<PassFactory> getChecks();

  /**
   * Gets the optimization passes to run.
   *
   * <p>Optimization passes revolve around producing smaller and faster code. They should always run
   * after checking passes.
   */
  protected abstract List<PassFactory> getOptimizations();

  /**
   * Gets the finalization passes to run.
   *
   * <p>Finalization passes include the injection of locale-specific code and converting the AST to
   * its final form for output.
   */
  protected abstract List<PassFactory> getFinalizations();

  /** Gets a graph of the passes run. For debugging. */
  GraphvizGraph getPassGraph() {
    LinkedDirectedGraph<String, String> graph = LinkedDirectedGraph.createWithoutAnnotations();
    Iterable<PassFactory> allPasses = Iterables.concat(getChecks(), getOptimizations());
    String lastPass = null;
    String loopStart = null;
    for (PassFactory pass : allPasses) {
      String passName = pass.getName();
      int i = 1;
      while (graph.hasNode(passName)) {
        passName = pass.getName() + (i++);
      }
      graph.createNode(passName);

      if (loopStart == null && pass.isRunInFixedPointLoop()) {
        loopStart = passName;
      } else if (loopStart != null && !pass.isRunInFixedPointLoop()) {
        graph.connect(lastPass, "loop", loopStart);
        loopStart = null;
      }

      if (lastPass != null) {
        graph.connect(lastPass, "", passName);
      }
      lastPass = passName;
    }
    return graph;
  }

  /** Insert the given pass factory before the factory of the given name. */
  static final void addPassFactoryBefore(
      List<PassFactory> factoryList, PassFactory factory, String passName) {
    factoryList.add(findPassIndexByName(factoryList, passName), factory);
  }

  /** Throws an exception if no pass with the given name exists. */
  static int findPassIndexByName(List<PassFactory> factoryList, String name) {
    for (int i = 0; i < factoryList.size(); i++) {
      if (factoryList.get(i).getName().equals(name)) {
        return i;
      }
    }

    throw new IllegalArgumentException("No factory named '" + name + "' in the factory list");
  }

  /** Find the first pass provider that does not have a delegate. */
  final PassConfig getBasePassConfig() {
    PassConfig current = this;
    while (current instanceof PassConfigDelegate) {
      current = ((PassConfigDelegate) current).delegate;
    }
    return current;
  }

  /** An implementation of PassConfig that just proxies all its method calls into an inner class. */
  public static class PassConfigDelegate extends PassConfig {

    private final PassConfig delegate;

    protected PassConfigDelegate(PassConfig delegate) {
      super(delegate.options);
      this.delegate = delegate;
    }

    @Override
    protected List<PassFactory> getWhitespaceOnlyPasses() {
      return delegate.getWhitespaceOnlyPasses();
    }

    @Override
    protected List<PassFactory> getChecks() {
      return delegate.getChecks();
    }

    @Override
    protected List<PassFactory> getOptimizations() {
      return delegate.getOptimizations();
    }

    @Override
    protected List<PassFactory> getFinalizations() {
      return delegate.getFinalizations();
    }

    @Override
    protected List<PassFactory> getTranspileOnlyPasses() {
      return delegate.getTranspileOnlyPasses();
    }
  }
}
