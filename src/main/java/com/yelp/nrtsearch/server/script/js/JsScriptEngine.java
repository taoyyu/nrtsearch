/*
 * Copyright 2020 Yelp Inc.
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
package com.yelp.nrtsearch.server.script.js;

import com.yelp.nrtsearch.server.field.FieldDefBindings;
import com.yelp.nrtsearch.server.script.ScoreScript;
import com.yelp.nrtsearch.server.script.ScriptContext;
import com.yelp.nrtsearch.server.script.ScriptEngine;
import java.text.ParseException;
import org.apache.lucene.expressions.Bindings;
import org.apache.lucene.expressions.Expression;
import org.apache.lucene.expressions.js.JavascriptCompiler;

/**
 * Script engine that provides a language based on javascript expressions. Expressions are compiled
 * and variables are bound using {@link JsScriptBindings}.
 *
 * @see <a
 *     href="https://lucene.apache.org/core/8_5_0/expressions/org/apache/lucene/expressions/js/package-summary.html">package
 *     docs/a>
 */
public class JsScriptEngine implements ScriptEngine {
  public static final String LANG = "js";

  /**
   * Get the script engine lang identifier for use in {@link com.yelp.nrtsearch.server.grpc.Script}.
   *
   * @return factory type identifier
   */
  @Override
  public String getLang() {
    return LANG;
  }

  /**
   * Compile the javascript expression source into query level factories, which can produce script
   * factories bound to a given index and parameters.
   *
   * @param source expression source string
   * @param context script context information used to create a factory of the proper type
   * @param <T> factory type needed for this script context
   * @return compiled script factory
   * @throws IllegalArgumentException if script context is not supported or javascript compile fails
   */
  @Override
  public <T> T compile(String source, ScriptContext<T> context) {
    if (!context.equals(ScoreScript.CONTEXT)) {
      throw new IllegalArgumentException("Unsupported script context: " + context.name);
    }
    Expression expr;
    try {
      expr = JavascriptCompiler.compile(source);
    } catch (ParseException pe) {
      // Static error (e.g. bad JavaScript syntax):
      throw new IllegalArgumentException(
          String.format("could not parse expression: %s", source), pe);
    }
    ScoreScript.Factory factory =
        ((params, docLookup) -> {
          Bindings fieldBindings = new FieldDefBindings(docLookup::getFieldDef);
          return expr.getDoubleValuesSource(new JsScriptBindings(fieldBindings, params));
        });
    return context.factoryClazz.cast(factory);
  }
}
