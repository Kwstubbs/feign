package feign;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;

/**
 * Expands the request-line supplied in the {@code value}, permitting path and query variables, or
 * just the http method.
 *
 * <p>
 *
 * <pre>
 * ...
 * &#64;RequestLine("POST /servers")
 * ...
 *
 * &#64;RequestLine("GET /servers/{serverId}?count={count}")
 * void get(&#64;Named("serverId") String serverId, &#64;Named("count") int count);
 * ...
 *
 * &#64;RequestLine("GET")
 * Response getNext(URI nextLink);
 * ...
 * </pre>
 *
 * HTTP version suffix is optional, but permitted. There are no guarantees this version will impact
 * that sent by the client.
 *
 * <p>
 *
 * <pre>
 * &#64;RequestLine("POST /servers HTTP/1.1")
 * ...
 * </pre>
 *
 * <p><strong>Note:</strong> Query params do not overwrite each other. All queries with the same
 * name will be included in the request.
 *
 * <h4>Relationship to JAXRS</h4>
 *
 * <p>The following two forms are identical.
 *
 * <p>Feign:
 *
 * <pre>
 * &#64;RequestLine("GET /servers/{serverId}?count={count}")
 * void get(&#64;Named("serverId") String serverId, &#64;Named("count") int count);
 * ...
 * </pre>
 *
 * <p>JAX-RS:
 *
 * <pre>
 * &#64;GET &#64;Path("/servers/{serverId}")
 * void get(&#64;PathParam("serverId") String serverId, &#64;QueryParam("count") int count);
 * ...
 * </pre>
 */
@java.lang.annotation.Target(METHOD)
@Retention(RUNTIME)
public @interface RequestLine {
  String value();
}
