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
package com.yelp.nrtsearch.server.field.properties;

import com.yelp.nrtsearch.server.field.FieldDef;
import org.apache.lucene.search.DoubleValuesSource;

/**
 * Trait interface for {@link FieldDef} types that can be bound into lucene {@link
 * org.apache.lucene.expressions.Expression} scripts.
 */
public interface Bindable {
  String VALUE_PROPERTY = "value";

  /**
   * Get {@link DoubleValuesSource} to produce values per document when this field is bound into a
   * lucene {@link org.apache.lucene.expressions.Expression} script.
   *
   * <p>Fields may allow for different properties to be bound. The 'value' property should return
   * the value of the field (or first value of a multi valued field). Numeric fields also define the
   * 'length' and 'empty' properties.
   *
   * @param property name of the property to bind
   * @return value source to use for expression script binding
   */
  DoubleValuesSource getExpressionBinding(String property);
}
