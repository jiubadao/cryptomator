/*******************************************************************************
 * Copyright (c) 2017 Skymatic UG (haftungsbeschränkt).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE file.
 *******************************************************************************/
package org.cryptomator.ui.model;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.cryptomator.cryptolib.api.CryptoException;
import org.cryptomator.frontend.webdav.mount.Mounter.CommandFailedException;
import org.cryptomator.keychain.KeychainAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AutoUnlocker {

	private static final Logger LOG = LoggerFactory.getLogger(AutoUnlocker.class);
	private static final int NAP_TIME_MILLIS = 500;

	private final Optional<KeychainAccess> keychainAccess;
	private final VaultList vaults;
	private final ExecutorService executor;

	@Inject
	public AutoUnlocker(Optional<KeychainAccess> keychainAccess, VaultList vaults, ExecutorService executor) {
		this.keychainAccess = keychainAccess;
		this.vaults = vaults;
		this.executor = executor;
	}

	public void unlockAllSilently() {
		Collection<Vault> vaultsToUnlock = vaults.stream().filter(this::shouldUnlockAfterStartup).collect(Collectors.toList());
		if (keychainAccess.isPresent() && !vaultsToUnlock.isEmpty()) {
			executor.submit(() -> unlockAll(vaultsToUnlock));
		}
	}

	private boolean shouldUnlockAfterStartup(Vault vault) {
		return vault.getVaultSettings().unlockAfterStartup().get();
	}

	private void unlockAll(Collection<Vault> vaults) {
		try {
			Iterator<Vault> iterator = vaults.iterator();
			assert iterator.hasNext() : "vaults must not be empty";
			unlockSilently(iterator.next());
			while (iterator.hasNext()) {
				Thread.sleep(NAP_TIME_MILLIS);
				unlockSilently(iterator.next());
			}
		} catch (InterruptedException e) {
			LOG.warn("Auto unlock thread interrupted.");
			Thread.currentThread().interrupt();
		}
	}

	private void unlockSilently(Vault vault) {
		char[] storedPw = keychainAccess.get().loadPassphrase(vault.getId());
		if (storedPw == null) {
			LOG.warn("No passphrase stored in keychain for vault registered for auto unlocking: {}", vault.getPath());
			return;
		}
		try {
			vault.unlock(CharBuffer.wrap(storedPw));
			mountSilently(vault);
		} catch (IOException | CryptoException e) {
			LOG.error("Auto unlock failed.", e);
		} finally {
			Arrays.fill(storedPw, ' ');
		}
	}

	private void mountSilently(Vault unlockedVault) {
		if (!unlockedVault.getVaultSettings().mountAfterUnlock().get()) {
			return;
		}
		try {
			unlockedVault.mount();
			revealSilently(unlockedVault);
		} catch (CommandFailedException e) {
			LOG.error("Auto unlock succeded, but mounting the drive failed.", e);
		}
	}

	private void revealSilently(Vault mountedVault) {
		if (!mountedVault.getVaultSettings().revealAfterMount().get()) {
			return;
		}
		try {
			mountedVault.reveal();
		} catch (CommandFailedException e) {
			LOG.error("Auto unlock succeded, but revealing the drive failed.", e);
		}
	}

}
