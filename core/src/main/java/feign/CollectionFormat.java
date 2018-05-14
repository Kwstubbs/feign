/**
 * Copyright 2012-2018 The Feign Authors
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign;

import java.util.Collection;

/**
 * Various ways to encode collections in URL parameters.
 *
 * <p>These specific cases are inspired by the <a href="http://swagger.io/specification/">OpenAPI
 * specification</a>.
 */
public enum CollectionFormat {
  /** Comma separated values, eg foo=bar,baz */
  CSV(","),
  /** Space separated values, eg foo=bar baz */
  SSV(" "),
  /** Tab separated values, eg foo=bar[tab]baz */
  TSV("\t"),
  /** Values separated with the pipe (|) character, eg foo=bar|baz */
  PIPES("|"),
  /** Parameter name repeated for each value, eg foo=bar&foo=baz */
  // Using null as a special case since there is no single separator character
  EXPLODED(null);

  private final String separator;

  CollectionFormat(String separator) {
    this.separator = separator;
  }

  /**
   * Joins the field and possibly multiple values with the given separator.
   *
   * <p>Calling EXPLODED.join("foo", ["bar"]) will return "foo=bar".
   *
   * <p>Calling CSV.join("foo", ["bar", "baz"]) will return "foo=bar,baz".
   *
   * <p>Null values are treated somewhat specially. With EXPLODED, the field is repeated without any
   * "=" for backwards compatibility. With all other formats, null values are not included in the
   * joined value list.
   *
   * @param field The field name corresponding to these values.
   * @param values A collection of value strings for the given field.
   * @return The formatted char sequence of the field and joined values. If the value collection is
   *     empty, an empty char sequence will be returned.
   */
  CharSequence join(String field, Collection<String> values) {
    StringBuilder builder = new StringBuilder();
    int valueCount = 0;
    for (String value : values) {
      if (separator == null) {
        // exploded
        builder.append(valueCount++ == 0 ? "" : "&");
        builder.append(field);
        if (value != null) {
          builder.append('=');
          builder.append(value);
        }
      } else {
        // delimited with a separator character
        if (builder.length() == 0) {
          builder.append(field);
        }
        if (value == null) {
          continue;
        }
        builder.append(valueCount++ == 0 ? "=" : separator);
        builder.append(value);
      }
    }
    return builder;
  }
}