/*******************************************************************************
 * Copyright (c) 2026 Cocotec Ltd and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Ahmed Hussain (Cocotec Ltd) - initial implementation
 *
 *******************************************************************************/
package org.eclipse.lsp4e.test.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4e.internal.DynamicRegistrationManager;
import org.eclipse.lsp4e.internal.JsonUtil;
import org.eclipse.lsp4e.internal.files.FileSystemWatcherManager;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionRegistrationOptions;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DynamicRegistrationManager}, exercising it in isolation from
 * {@link org.eclipse.lsp4e.LanguageServerWrapper} (the end-to-end behaviour is covered by
 * {@code DynamicRegistrationTest}).
 */
class DynamicRegistrationManagerTest {

	private static final String CODE_ACTION = "textDocument/codeAction";
	private static final String WATCHED_FILES = "workspace/didChangeWatchedFiles";

	private record CapabilitiesChange(@Nullable ServerCapabilities oldCapabilities,
			ServerCapabilities newCapabilities) {
	}

	private final List<CapabilitiesChange> changes = new ArrayList<>();
	private final FileSystemWatcherManager watcherManager = new FileSystemWatcherManager((Path) null);
	private final DynamicRegistrationManager manager = new DynamicRegistrationManager(watcherManager,
			(oldCaps, newCaps) -> changes.add(new CapabilitiesChange(oldCaps, newCaps)));

	private static ServerCapabilities staticCapabilities() {
		final var caps = new ServerCapabilities();
		caps.setCodeActionProvider(Boolean.FALSE);
		return caps;
	}

	/** Registers via a raw JSON payload, as arriving over the wire. */
	private void register(String id, String method, @Nullable Object options) {
		final var registration = new Registration(id, method);
		if (options != null) {
			registration.setRegisterOptions(JsonUtil.LSP4J_GSON.toJsonTree(options));
		}
		manager.registerCapability(new RegistrationParams(List.of(registration)));
	}

	private void unregister(String id, String method) {
		manager.unregisterCapability(new UnregistrationParams(List.of(new Unregistration(id, method))));
	}

	private static CodeActionRegistrationOptions codeActionOptions(String kind) {
		final var options = new CodeActionRegistrationOptions();
		options.setCodeActionKinds(List.of(kind));
		return options;
	}

	private List<String> effectiveCodeActionKinds() {
		final ServerCapabilities caps = manager.getCapabilities();
		assertNotNull(caps);
		final Either<Boolean, CodeActionOptions> provider = caps.getCodeActionProvider();
		assertNotNull(provider);
		assertTrue(provider.isRight(), "expected CodeActionOptions but was: " + provider);
		return provider.getRight().getCodeActionKinds();
	}

	@Test
	void nullBeforeInitialization() {
		assertNull(manager.getCapabilities());
	}

	@Test
	void staticCapabilitiesAreEffectiveUntilFirstRegistration() {
		final ServerCapabilities staticCaps = staticCapabilities();
		manager.setStaticCapabilities(staticCaps);
		assertSame(staticCaps, manager.getCapabilities());
		assertTrue(changes.isEmpty(), "setStaticCapabilities must not notify the listener");
	}

	@Test
	void registerDecodesJsonPayloadAndNotifiesListener() {
		manager.setStaticCapabilities(staticCapabilities());

		final var options = codeActionOptions(CodeActionKind.QuickFix);
		options.setResolveProvider(Boolean.TRUE);
		register("r1", CODE_ACTION, options);

		assertEquals(List.of(CodeActionKind.QuickFix), effectiveCodeActionKinds());
		final ServerCapabilities caps = manager.getCapabilities();
		assertNotNull(caps);
		assertEquals(Boolean.TRUE, caps.getCodeActionProvider().getRight().getResolveProvider());

		assertEquals(1, changes.size());
		final CapabilitiesChange change = changes.get(0);
		final ServerCapabilities oldCaps = change.oldCapabilities();
		assertNotNull(oldCaps);
		assertEquals(Boolean.FALSE, oldCaps.getCodeActionProvider().getLeft());
		assertSame(caps, change.newCapabilities());
	}

	@Test
	void absentRegisterOptionsFallBackToTrue() {
		manager.setStaticCapabilities(staticCapabilities());

		register("r1", CODE_ACTION, null);
		ServerCapabilities caps = manager.getCapabilities();
		assertNotNull(caps);
		assertEquals(Boolean.TRUE, caps.getCodeActionProvider().getLeft());

		unregister("r1", CODE_ACTION);
		caps = manager.getCapabilities();
		assertNotNull(caps);
		assertEquals(Boolean.FALSE, caps.getCodeActionProvider().getLeft());
	}

	@Test
	void lastRegistrationWins() {
		manager.setStaticCapabilities(staticCapabilities());

		register("r1", CODE_ACTION, codeActionOptions(CodeActionKind.QuickFix));
		register("r2", CODE_ACTION, codeActionOptions(CodeActionKind.Refactor));
		assertEquals(List.of(CodeActionKind.Refactor), effectiveCodeActionKinds());
	}

	@Test
	void unregistrationOrderDoesNotMatter() {
		manager.setStaticCapabilities(staticCapabilities());

		// unregister the winner: falls back to the older live registration
		register("r1", CODE_ACTION, codeActionOptions(CodeActionKind.QuickFix));
		register("r2", CODE_ACTION, codeActionOptions(CodeActionKind.Refactor));
		unregister("r2", CODE_ACTION);
		assertEquals(List.of(CodeActionKind.QuickFix), effectiveCodeActionKinds());
		unregister("r1", CODE_ACTION);

		// unregister the loser first: the winner stays in effect
		register("r3", CODE_ACTION, codeActionOptions(CodeActionKind.QuickFix));
		register("r4", CODE_ACTION, codeActionOptions(CodeActionKind.Refactor));
		unregister("r3", CODE_ACTION);
		assertEquals(List.of(CodeActionKind.Refactor), effectiveCodeActionKinds());
		unregister("r4", CODE_ACTION);

		// all live registrations gone: back to the static capabilities
		final ServerCapabilities caps = manager.getCapabilities();
		assertNotNull(caps);
		assertEquals(Boolean.FALSE, caps.getCodeActionProvider().getLeft());
	}

	@Test
	void watchedFilesRegistrationsAreForwardedToTheWatcherManager() {
		manager.setStaticCapabilities(staticCapabilities());
		assertFalse(watcherManager.hasFilePatterns());

		register("w1", WATCHED_FILES, new DidChangeWatchedFilesRegistrationOptions(
				List.of(new FileSystemWatcher(Either.forLeft("**/*.txt")))));
		assertTrue(watcherManager.hasFilePatterns());
		assertEquals(1, changes.size(), "recompute must still notify the listener");

		unregister("w1", WATCHED_FILES);
		assertFalse(watcherManager.hasFilePatterns());
		assertEquals(2, changes.size());
	}

	@Test
	void clearResetsEverythingWithoutNotifying() {
		manager.setStaticCapabilities(staticCapabilities());
		register("w1", WATCHED_FILES, new DidChangeWatchedFilesRegistrationOptions(
				List.of(new FileSystemWatcher(Either.forLeft("**/*.txt")))));
		changes.clear();

		manager.clear();
		assertNull(manager.getCapabilities());
		assertFalse(watcherManager.hasFilePatterns());
		assertTrue(changes.isEmpty(), "clear() must not notify the listener");
	}
}
