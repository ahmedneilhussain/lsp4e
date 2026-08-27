/*******************************************************************************
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   See git history
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * Provides a {@link Gson} instance which can properly serialize and deserialize LSP4J JSON-RPC objects
 */
public class JsonUtil {

	public static final Gson LSP4J_GSON = Objects.requireNonNull(new MessageJsonHandler(Map.of()).getGson());


	/**
	 * Helper for dynamic registration: if the payload is a top-level Either then we can't
	 * just pass Either.class to GSON, we need to use TypeTokens to avoid type erasure
	 * @param registration
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings({ "unchecked", "null" })
	public static @Nullable Either<Boolean, CodeActionOptions> unserializeCodeActionRegistration(Registration registration) throws Exception {
		TypeToken<Either<Boolean, CodeActionOptions>> type = new TypeToken<Either<Boolean, CodeActionOptions>>() {};
		var payload = (JsonElement)registration.getRegisterOptions();

		return (Either<Boolean, CodeActionOptions>)LSP4J_GSON.fromJson(payload, type.getType());

	}

}
