/**
 * Copyright 2012-2019 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.Request.HttpMethod;
import org.assertj.core.util.Lists;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static feign.assertj.FeignAssertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class ResponseTest {

    @Test
    public void reasonPhraseIsOptional() {
        Response response = Response.builder()
                .status(200)
                .headers(Collections.<String, Collection<String>>emptyMap())
                .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                .body(new byte[0])
                .build();

        assertThat(response.reason()).isNull();
        assertThat(response.toString()).isEqualTo("HTTP/1.1 200\n\n");
    }

    @Test
    public void canAccessHeadersCaseInsensitively() {
        Map<String, Collection<String>> headersMap = new LinkedHashMap();
        List<String> valueList = Collections.singletonList("application/json");
        headersMap.put("Content-Type", valueList);
        Response response = Response.builder()
                .status(200)
                .headers(headersMap)
                .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                .body(new byte[0])
                .build();
        assertThat(response.headers().get("content-type")).isEqualTo(valueList);
        assertThat(response.headers().get("Content-Type")).isEqualTo(valueList);
    }

    @Test
    public void headerValuesWithSameNameOnlyVaryingInCaseAreMerged() {
        Map<String, Collection<String>> headersMap = new LinkedHashMap();
        headersMap.put("Set-Cookie", Arrays.asList("Cookie-A=Value", "Cookie-B=Value"));
        headersMap.put("set-cookie", Arrays.asList("Cookie-C=Value"));

        Response response = Response.builder()
                .status(200)
                .headers(headersMap)
                .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                .body(new byte[0])
                .build();

        List<String> expectedHeaderValue =
                Arrays.asList("Cookie-A=Value", "Cookie-B=Value", "Cookie-C=Value");
        assertThat(response.headers()).containsOnly(entry(("set-cookie"), expectedHeaderValue));
    }

    @Test
    public void headersAreOptional() {
        Response response = Response.builder()
                .status(200)
                .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                .body(new byte[0])
                .build();
        assertThat(response.headers()).isNotNull().isEmpty();
    }

    @Test
    public void support1xxStatusCodes() {
        Response response = Response.builder()
                .status(103)
                .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                .body((Response.Body) null)
                .build();

        assertThat(response.status()).isEqualTo(103);
    }

    @Test
    public void statusCodesOfAnyValueAreAllowed() {
        Lists.list(600, 50, 35600).forEach(statusCode -> {
            Response response = Response.builder()
                    .status(statusCode)
                    .request(Request.create(HttpMethod.GET, "/api", Collections.emptyMap(), null, Util.UTF_8))
                    .body((Response.Body) null)
                    .build();

            assertThat(response.status()).isEqualTo(statusCode);
        });
    }
}
