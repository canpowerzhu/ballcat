/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hccake.ballcat.common.security.oauth2.server.resource;

import cn.hutool.core.collection.CollectionUtil;
import com.hccake.ballcat.common.security.constant.TokenAttributeNameConstants;
import com.hccake.ballcat.common.security.constant.UserInfoFiledNameConstants;
import com.hccake.ballcat.common.security.userdetails.ClientPrincipal;
import com.hccake.ballcat.common.security.userdetails.User;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.TokenIntrospectionResponse;
import com.nimbusds.oauth2.sdk.TokenIntrospectionSuccessResponse;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.Audience;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.NimbusOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionClaimNames;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * copy from {@link NimbusOpaqueTokenIntrospector}，重写了 OAuth2AuthenticatedPrincipal
 * 的构建，保持项目内统一使用 {@link com.hccake.ballcat.common.security.userdetails.User}
 *
 * A Nimbus implementation of {@link OpaqueTokenIntrospector} that verifies and
 * introspects a token using the configured
 * <a href="https://tools.ietf.org/html/rfc7662" target="_blank">OAuth 2.0 Introspection
 * Endpoint</a>.
 *
 * @author Josh Cummings
 * @author MD Sayem Ahmed
 * @since 5.2
 */
public class RemoteOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private Converter<String, RequestEntity<?>> requestEntityConverter;

	private RestOperations restOperations;

	private static final String AUTHORITY_SCOPE_PREFIX = "SCOPE_";

	/**
	 * Creates a {@code OpaqueTokenAuthenticationProvider} with the provided parameters
	 * @param introspectionUri The introspection endpoint uri
	 * @param clientId The client id authorized to introspect
	 * @param clientSecret The client's secret
	 */
	public RemoteOpaqueTokenIntrospector(String introspectionUri, String clientId, String clientSecret) {
		Assert.notNull(introspectionUri, "introspectionUri cannot be null");
		Assert.notNull(clientId, "clientId cannot be null");
		Assert.notNull(clientSecret, "clientSecret cannot be null");
		this.requestEntityConverter = this.defaultRequestEntityConverter(URI.create(introspectionUri));
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(clientId, clientSecret));
		this.restOperations = restTemplate;
	}

	/**
	 * Creates a {@code OpaqueTokenAuthenticationProvider} with the provided parameters
	 *
	 * The given {@link RestOperations} should perform its own client authentication
	 * against the introspection endpoint.
	 * @param introspectionUri The introspection endpoint uri
	 * @param restOperations The client for performing the introspection request
	 */
	public RemoteOpaqueTokenIntrospector(String introspectionUri, RestOperations restOperations) {
		Assert.notNull(introspectionUri, "introspectionUri cannot be null");
		Assert.notNull(restOperations, "restOperations cannot be null");
		this.requestEntityConverter = this.defaultRequestEntityConverter(URI.create(introspectionUri));
		this.restOperations = restOperations;
	}

	private Converter<String, RequestEntity<?>> defaultRequestEntityConverter(URI introspectionUri) {
		return (token) -> {
			HttpHeaders headers = requestHeaders();
			MultiValueMap<String, String> body = requestBody(token);
			return new RequestEntity<>(body, headers, HttpMethod.POST, introspectionUri);
		};
	}

	private HttpHeaders requestHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
		return headers;
	}

	private MultiValueMap<String, String> requestBody(String token) {
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("token", token);
		return body;
	}

	@Override
	public OAuth2AuthenticatedPrincipal introspect(String token) {
		RequestEntity<?> requestEntity = this.requestEntityConverter.convert(token);
		if (requestEntity == null) {
			throw new OAuth2IntrospectionException("requestEntityConverter returned a null entity");
		}
		ResponseEntity<String> responseEntity = makeRequest(requestEntity);
		HTTPResponse httpResponse = adaptToNimbusResponse(responseEntity);
		TokenIntrospectionResponse introspectionResponse = parseNimbusResponse(httpResponse);
		TokenIntrospectionSuccessResponse introspectionSuccessResponse = castToNimbusSuccess(introspectionResponse);
		// relying solely on the authorization server to validate this token (not checking
		// 'exp', for example)
		if (!introspectionSuccessResponse.isActive()) {
			this.logger.trace("Did not validate token since it is inactive");
			throw new BadOpaqueTokenException("Provided token isn't active");
		}
		return convertClaimsSet(introspectionSuccessResponse);
	}

	/**
	 * Sets the {@link Converter} used for converting the OAuth 2.0 access token to a
	 * {@link RequestEntity} representation of the OAuth 2.0 token introspection request.
	 * @param requestEntityConverter the {@link Converter} used for converting to a
	 * {@link RequestEntity} representation of the token introspection request
	 */
	public void setRequestEntityConverter(Converter<String, RequestEntity<?>> requestEntityConverter) {
		Assert.notNull(requestEntityConverter, "requestEntityConverter cannot be null");
		this.requestEntityConverter = requestEntityConverter;
	}

	private ResponseEntity<String> makeRequest(RequestEntity<?> requestEntity) {
		try {
			return this.restOperations.exchange(requestEntity, String.class);
		}
		catch (Exception ex) {
			throw new OAuth2IntrospectionException(ex.getMessage(), ex);
		}
	}

	private HTTPResponse adaptToNimbusResponse(ResponseEntity<String> responseEntity) {
		HTTPResponse response = new HTTPResponse(responseEntity.getStatusCodeValue());
		MediaType contentType = responseEntity.getHeaders().getContentType();
		if (contentType != null) {
			response.setHeader(HttpHeaders.CONTENT_TYPE, contentType.toString());
		}
		response.setContent(responseEntity.getBody());
		if (response.getStatusCode() != HTTPResponse.SC_OK) {
			throw new OAuth2IntrospectionException("Introspection endpoint responded with " + response.getStatusCode());
		}
		return response;
	}

	private TokenIntrospectionResponse parseNimbusResponse(HTTPResponse response) {
		try {
			return TokenIntrospectionResponse.parse(response);
		}
		catch (Exception ex) {
			throw new OAuth2IntrospectionException(ex.getMessage(), ex);
		}
	}

	private TokenIntrospectionSuccessResponse castToNimbusSuccess(TokenIntrospectionResponse introspectionResponse) {
		if (!introspectionResponse.indicatesSuccess()) {
			throw new OAuth2IntrospectionException("Token introspection failed");
		}
		return (TokenIntrospectionSuccessResponse) introspectionResponse;
	}

	private OAuth2AuthenticatedPrincipal convertClaimsSet(TokenIntrospectionSuccessResponse response) {
		Map<String, Object> claims = new HashMap<>(16);
		if (response.getAudience() != null) {
			List<String> audiences = new ArrayList<>();
			for (Audience audience : response.getAudience()) {
				audiences.add(audience.getValue());
			}
			claims.put(OAuth2IntrospectionClaimNames.AUDIENCE, Collections.unmodifiableList(audiences));
		}
		if (response.getClientID() != null) {
			claims.put(OAuth2IntrospectionClaimNames.CLIENT_ID, response.getClientID().getValue());
		}
		if (response.getExpirationTime() != null) {
			Instant exp = response.getExpirationTime().toInstant();
			claims.put(OAuth2IntrospectionClaimNames.EXPIRES_AT, exp);
		}
		if (response.getIssueTime() != null) {
			Instant iat = response.getIssueTime().toInstant();
			claims.put(OAuth2IntrospectionClaimNames.ISSUED_AT, iat);
		}
		if (response.getIssuer() != null) {
			claims.put(OAuth2IntrospectionClaimNames.ISSUER, issuer(response.getIssuer().getValue()));
		}
		if (response.getNotBeforeTime() != null) {
			claims.put(OAuth2IntrospectionClaimNames.NOT_BEFORE, response.getNotBeforeTime().toInstant());
		}

		if (response.getScope() != null) {
			List<String> scopes = Collections.unmodifiableList(response.getScope().toStringList());
			claims.put(OAuth2IntrospectionClaimNames.SCOPE, scopes);
		}

		boolean isClient;
		try {
			isClient = response.getBooleanParameter("is_client");
		}
		catch (ParseException e) {
			logger.warn("自定端点返回的 is_client 属性解析异常: {}, 请求信息：[{}]", e.getMessage(), response.toJSONObject());
			isClient = false;
		}
		return isClient ? buildClient(claims) : buildUser(response.toJSONObject(), claims);

	}

	@SuppressWarnings("unchecked")
	private ClientPrincipal buildClient(Map<String, Object> claims) {
		String clientId = (String) claims.get(OAuth2IntrospectionClaimNames.CLIENT_ID);

		List<String> scopes = null;
		Object scopeValue = claims.get(OAuth2IntrospectionClaimNames.SCOPE);
		if (scopeValue instanceof List) {
			scopes = (List<String>) scopeValue;
		}

		Collection<GrantedAuthority> authorities = new ArrayList<>();
		if (CollectionUtil.isNotEmpty(scopes)) {
			for (String scope : scopes) {
				authorities.add(new SimpleGrantedAuthority(AUTHORITY_SCOPE_PREFIX + scope));
			}
		}

		ClientPrincipal clientPrincipal = new ClientPrincipal(clientId, claims, authorities);
		clientPrincipal.setScope(scopes);

		return clientPrincipal;
	}

	/**
	 * 根据返回值信息，反向构建出 User 对象
	 * @param responseBody 响应体信息
	 * @param claims attributes
	 * @return User
	 */
	private User buildUser(JSONObject responseBody, Map<String, Object> claims) {
		User.UserBuilder builder = User.builder();

		JSONObject info = (JSONObject) responseBody.getOrDefault(TokenAttributeNameConstants.INFO, new JSONObject());

		Number userIdNumber = info.getAsNumber(UserInfoFiledNameConstants.USER_ID);
		if (userIdNumber != null) {
			builder.userId(userIdNumber.intValue());
		}

		Number typeNumber = info.getAsNumber(UserInfoFiledNameConstants.TYPE);
		if (typeNumber != null) {
			builder.type(typeNumber.intValue());
		}

		Number organizationIdNumber = info.getAsNumber(UserInfoFiledNameConstants.ORGANIZATION_ID);
		if (organizationIdNumber != null) {
			builder.organizationId(organizationIdNumber.intValue());
		}

		builder.username(info.getAsString(UserInfoFiledNameConstants.USERNAME))
				.nickname(info.getAsString(UserInfoFiledNameConstants.NICKNAME))
				.avatar(info.getAsString(UserInfoFiledNameConstants.AVATAR)).status(1);

		Object authoritiesJSONArray = responseBody.get("authorities");
		if (authoritiesJSONArray != null) {
			Collection<? extends GrantedAuthority> authorities = AuthorityUtils
					.createAuthorityList(((JSONArray) authoritiesJSONArray).toArray(new String[0]));
			builder.authorities(authorities);
		}

		Object attribute = responseBody.get(TokenAttributeNameConstants.ATTRIBUTES);
		if (attribute != null) {
			claims.putAll((JSONObject) attribute);
		}
		builder.attributes(claims);

		return builder.build();
	}

	private URL issuer(String uri) {
		try {
			return new URL(uri);
		}
		catch (Exception ex) {
			throw new OAuth2IntrospectionException(
					"Invalid " + OAuth2IntrospectionClaimNames.ISSUER + " value: " + uri);
		}
	}

}
