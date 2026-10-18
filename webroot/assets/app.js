(() => {
    'use strict';

    const BIN = '/data/adb/modules/wadbd/system/bin/wadbd';
    const $ = (id) => document.getElementById(id);
    let pollTimer = null;
    let toastTimer = null;
    let busy = false;
    let supported = false;
    let wirelessPort = '';
    let authSupported = false;
    let authMode = 'secure';
    let moduleVersion = '';
    let latestStatus = null;

    function shellQuote(value) {
        return `'${String(value).replace(/'/g, `'\\''`)}'`;
    }

    function stripAnsi(value) {
        return String(value || '').replace(/\u001b\[[0-9;]*m/g, '').trim();
    }

    function run(command) {
        return new Promise((resolve, reject) => {
            if (!window.ksu || typeof window.ksu.exec !== 'function') {
                reject(new Error('Root manager command access is unavailable.'));
                return;
            }
            const callbackName = `wadbd_exec_${Date.now()}_${Math.random().toString(36).slice(2)}`;
            window[callbackName] = (errno, stdout, stderr) => {
                delete window[callbackName];
                const result = { errno: Number(errno) || 0, stdout: String(stdout || ''), stderr: String(stderr || '') };
                if (result.errno !== 0) {
                    reject(new Error(stripAnsi(result.stderr || result.stdout) || `Command failed (${result.errno}).`));
                } else {
                    resolve(result);
                }
            };
            try {
                window.ksu.exec(command, '{}', callbackName);
            } catch (error) {
                delete window[callbackName];
                reject(error);
            }
        });
    }

    function toast(message) {
        const node = $('toast');
        node.textContent = message;
        node.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { node.hidden = true; }, 3200);
        if (window.ksu && typeof window.ksu.toast === 'function') window.ksu.toast(message);
    }

    function setControlsEnabled(enabled) {
        $('wirelessToggle').disabled = !enabled;
        $('portInput').disabled = !enabled;
        $('applyPortButton').disabled = !enabled;
        $('bootToggle').disabled = !enabled;
        $('notificationToggle').disabled = !enabled;
        $('refreshBindingsButton').disabled = !enabled || !wirelessPort;
        $('customTargetInput').disabled = !enabled || !wirelessPort;
        $('customTargetForm').querySelector('button').disabled = !enabled || !wirelessPort;
        $('refreshKeysButton').disabled = !enabled;
        $('importKeyButton').disabled = !enabled || authMode === 'open';
        $('clearKeysButton').disabled = !enabled || authMode === 'open';
        $('authToggle').disabled = !enabled || !authSupported;
    }

    function showToastResult(label, result) {
        const detail = stripAnsi(result.stdout).split('\n').map((line) => line.trim()).filter(Boolean).pop();
        toast(detail || `${label} updated.`);
    }

    async function checkEnvironment() {
        if (window.ksu && typeof window.ksu.exec === 'function') {
            try {
                await run('id');
                supported = true;
            } catch {
                supported = false;
            }
        }
        if (!supported) {
            $('environmentNotice').hidden = false;
            const hasRequestBridge = window.mmrl && window.$wadbd && typeof window.$wadbd.requestAdvancedKernelSUAPI === 'function';
            $('requestApiButton').hidden = !hasRequestBridge;
            $('environmentMessage').textContent = hasRequestBridge
                ? 'Grant KernelSU command access to control WADBD from this page.'
                : 'Open this page from KernelSU, APatch, or a compatible WebUI manager.';
            setControlsEnabled(false);
            return false;
        }
        $('environmentNotice').hidden = true;
        return true;
    }

    async function readStatus() {
        const { stdout } = await run(`${shellQuote(BIN)} status --json`);
        let status;
        try {
            status = JSON.parse(stdout);
        } catch {
            throw new Error('Failed to parse daemon status response.');
        }
        latestStatus = status;

        wirelessPort = (status.enabled && status.port > 0) ? String(status.port) : '';
        const bootPort = (status.boot_port && status.boot_port > 0) ? String(status.boot_port) : (wirelessPort || '5555');
        const notifyEnabled = Boolean(status.notify);
        const daemon = status.adbd_status || 'unknown';
        const clients = (status.sessions && Array.isArray(status.sessions)) ? status.sessions.length : 0;
        authMode = status.auth_mode === 'open' ? 'open' : 'secure';
        authSupported = Boolean(status.auth_supported);

        $('adbHeading').textContent = wirelessPort ? `Wireless ADB is on` : 'Wireless ADB is off';
        $('adbSummary').textContent = wirelessPort ? `Listening on port ${wirelessPort}.` : 'Turn it on to connect over Wi-Fi or another network.';
        $('wirelessToggle').checked = Boolean(wirelessPort);
        if (!document.activeElement || document.activeElement !== $('portInput')) {
            $('portInput').value = wirelessPort || bootPort;
        }
        $('daemonState').textContent = daemon;
        $('clientState').textContent = `${clients} ${clients === 1 ? 'session' : 'sessions'}`;
        $('bootToggle').checked = Boolean(status.boot_enabled);
        $('bootSummary').textContent = status.boot_enabled ? `Will start on port ${bootPort} after boot.` : 'Wireless ADB stays off after reboot.';
        $('notificationToggle').checked = notifyEnabled;
        $('notificationSummary').textContent = notifyEnabled ? 'Shell alerts are enabled.' : 'Shell alerts are disabled.';
        $('authToggle').checked = authMode === 'open';
        $('authHelp').textContent = 'Skip the RSA trust prompt for ADB clients. This may also affect USB ADB. It does not grant root by itself.';
        $('authSummary').textContent = authSupported
            ? (authMode === 'open' ? 'RSA checks are off. Any reachable ADB client can connect.' : 'RSA trust is required for new clients.')
            : (authMode === 'open' ? 'Open mode is saved, but resetprop is unavailable to apply it.' : 'A resetprop tool from the root manager is needed to change adbd authentication.');
        $('keyModeNote').hidden = authMode !== 'open';
        $('keysDescription').textContent = authMode === 'open'
            ? 'Saved keys remain on the phone, but adbd does not enforce them while unauthenticated mode is active.'
            : 'SHA-256 fingerprints for authorized ADB keys.';
        $('versionLabel').textContent = moduleVersion ? `Version ${moduleVersion}` : 'WADBD Daemon v8.0';
        $('connectionSummary').textContent = wirelessPort ? `Port ${wirelessPort}` : 'Available when Wireless ADB is on';
        setControlsEnabled(true);
        if (wirelessPort && $('connectionDetails').open) refreshConnectionCommands();
    }

    function refreshConnectionCommands() {
        const host = $('connectionCommands');
        if (!wirelessPort) {
            host.replaceChildren(makeText('p', 'muted', 'Enable Wireless ADB to see commands for this device.'));
            return;
        }
        const addresses = (latestStatus && Array.isArray(latestStatus.addresses))
            ? latestStatus.addresses.filter((a) => a.allowed && a.interface !== 'lo')
            : [];

        host.replaceChildren();
        if (!addresses.length) {
            host.append(makeText('p', 'muted', (latestStatus && latestStatus.bindings && latestStatus.bindings.length)
                ? 'No selected network has an active address. Check the connection limits below.'
                : 'No network address is available yet. Connect to Wi-Fi or another network and refresh.'));
            return;
        }
        addresses.forEach(({ interface: iface, ip }) => {
            const row = document.createElement('div');
            row.className = 'command-item';
            const code = document.createElement('code');
            code.textContent = `adb connect ${ip}:${wirelessPort}`;
            const copy = document.createElement('button');
            copy.className = 'button secondary';
            copy.type = 'button';
            copy.textContent = 'Copy';
            copy.setAttribute('aria-label', `Copy ADB command for ${iface}`);
            copy.addEventListener('click', () => copyText(code.textContent));
            row.append(code, copy);
            host.append(row);
        });
    }

    function makeText(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        node.textContent = text;
        return node;
    }

    async function copyText(value) {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) await navigator.clipboard.writeText(value);
            else {
                const area = document.createElement('textarea');
                area.value = value;
                area.style.position = 'fixed';
                area.style.opacity = '0';
                document.body.append(area);
                area.select();
                document.execCommand('copy');
                area.remove();
            }
            toast('ADB command copied.');
        } catch {
            toast('Could not copy the command.');
        }
    }

    async function refreshBindings() {
        const host = $('bindingList');
        if (!supported) return;
        host.replaceChildren(makeText('p', 'placeholder', 'Loading network interfaces…'));
        try {
            const linkResult = await run('ip -o link show 2>/dev/null');
            const available = linkResult.stdout.split(/\r?\n/).map((line) => {
                const match = line.match(/^\s*\d+:\s+([^:]+):/);
                return match ? match[1].split('@')[0] : '';
            }).filter((name) => name && name !== 'lo');

            const bound = new Set(latestStatus && latestStatus.bindings ? latestStatus.bindings : []);
            const targets = new Set([...available, 'tun+', '100.96.0.0/12', '100.64.0.0/10', ...bound]);
            host.replaceChildren();
            if (!targets.size) host.append(makeText('p', 'placeholder', 'No network interfaces found.'));
            [...targets].sort((a, b) => a.localeCompare(b)).forEach((target) => {
                const row = document.createElement('label');
                row.className = 'binding-row';
                const copy = document.createElement('span');
                copy.className = 'binding-copy';
                copy.append(makeText('span', 'binding-name', target));
                copy.append(makeText('span', 'binding-detail', bound.has(target) ? 'Allowed to reach ADB' : 'No restriction'));
                const toggle = document.createElement('input');
                toggle.type = 'checkbox';
                toggle.className = 'binding-toggle';
                toggle.checked = bound.has(target);
                toggle.disabled = !wirelessPort;
                toggle.setAttribute('aria-label', `${toggle.checked ? 'Remove' : 'Allow'} ${target} ${toggle.checked ? 'restriction' : 'for ADB'}`);
                toggle.addEventListener('change', async () => {
                    toggle.disabled = true;
                    try {
                        await run(`${shellQuote(BIN)} ${toggle.checked ? 'bind' : 'unbind'} ${shellQuote(target)}`);
                        toast(toggle.checked ? `${target} allowed.` : `${target} restriction removed.`);
                        await refreshAll();
                        await refreshBindings();
                    } catch (error) {
                        toggle.checked = !toggle.checked;
                        toast(error.message);
                    }
                });
                row.append(copy, toggle);
                host.append(row);
            });
            $('bindingSummary').textContent = bound.size
                ? `${bound.size} target${bound.size === 1 ? '' : 's'} selected. Other networks are blocked.`
                : 'No limits set. All network interfaces can reach ADB.';
            $('refreshBindingsButton').disabled = !wirelessPort;
            $('customTargetInput').disabled = !wirelessPort;
            $('customTargetForm').querySelector('button').disabled = !wirelessPort;
        } catch (error) {
            host.replaceChildren(makeText('p', 'placeholder', error.message || 'Could not read network interfaces.'));
            $('bindingSummary').textContent = 'Refresh to try again.';
        }
    }

    async function refreshKeys() {
        const host = $('keyList');
        host.replaceChildren(makeText('p', 'placeholder', 'Loading authorized keys…'));
        try {
            const { stdout } = await run(`${shellQuote(BIN)} list-keys --json`);
            let keys = [];
            try {
                keys = JSON.parse(stdout);
            } catch {
                keys = [];
            }
            host.replaceChildren();
            if (!keys || !keys.length) {
                host.append(makeText('p', 'placeholder', 'No authorized keys found.'));
                return;
            }
            keys.forEach((key) => {
                const row = document.createElement('div');
                row.className = 'key-row';
                const copy = document.createElement('span');
                copy.className = 'key-copy';
                copy.append(makeText('span', 'key-name', key.fingerprint || key.comment || 'Key #' + key.id));
                if (key.comment && key.fingerprint) {
                    copy.append(makeText('span', 'binding-detail', key.comment));
                }
                const remove = document.createElement('button');
                remove.className = 'button key-remove';
                remove.type = 'button';
                remove.textContent = 'Revoke';
                remove.disabled = authMode === 'open';
                remove.setAttribute('aria-label', `Revoke authorized key ${key.id}`);
                remove.addEventListener('click', async () => {
                    if (!window.confirm(`Revoke authorized key ${key.id}?`)) return;
                    try {
                        await run(`${shellQuote(BIN)} --remove-key ${shellQuote(key.id)}`);
                        toast('Authorized key revoked.');
                        await refreshKeys();
                    } catch (error) { toast(error.message); }
                });
                row.append(copy, remove);
                host.append(row);
            });
        } catch (error) {
            host.replaceChildren(makeText('p', 'placeholder', error.message || 'Could not read authorized keys.'));
        }
    }

    async function perform(label, action, refresh = true) {
        if (busy) return;
        busy = true;
        setControlsEnabled(false);
        try {
            const result = await run(action);
            showToastResult(label, result);
            if (refresh) await refreshAll();
        } catch (error) {
            toast(error.message || `${label} failed.`);
            await refreshAll();
        } finally {
            busy = false;
            setControlsEnabled(supported);
        }
    }

    async function refreshAll() {
        if (!supported) return;
        try {
            await readStatus();
        } catch (error) {
            $('adbHeading').textContent = 'Could not read status';
            $('adbSummary').textContent = error.message || 'Refresh to try again.';
        }
    }

    function validPort() {
        const port = Number($('portInput').value);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
            toast('Enter a port between 1 and 65535.');
            $('portInput').focus();
            return '';
        }
        return String(port);
    }

    $('refreshButton').addEventListener('click', async () => {
        await refreshAll();
        if ($('connectionDetails').open) refreshConnectionCommands();
    });
    $('requestApiButton').addEventListener('click', async () => {
        try {
            await window.$wadbd.requestAdvancedKernelSUAPI();
            toast('Access requested. Grant it in your root manager, then refresh.');
        } catch { toast('Could not request KernelSU access.'); }
    });
    $('wirelessToggle').addEventListener('change', () => {
        const enabled = $('wirelessToggle').checked;
        const port = enabled ? validPort() : '';
        if (enabled && !port) { $('wirelessToggle').checked = Boolean(wirelessPort); return; }
        perform('Wireless ADB', `${shellQuote(BIN)} ${enabled ? `on ${port}` : 'off'}`);
    });
    $('applyPortButton').addEventListener('click', () => {
        const port = validPort();
        if (!port) return;
        const bootOn = $('bootToggle').checked;
        const operation = wirelessPort
            ? `${shellQuote(BIN)} on ${port}${bootOn ? ` && ${shellQuote(BIN)} enable-on-boot ${port}` : ''}`
            : (bootOn ? `${shellQuote(BIN)} enable-on-boot ${port}` : '');
        if (!operation) { toast('Port saved when Wireless ADB or Start on boot is enabled.'); return; }
        perform('ADB port', operation);
    });
    $('bootToggle').addEventListener('change', () => {
        const enabled = $('bootToggle').checked;
        const port = validPort();
        if (enabled && !port) { $('bootToggle').checked = false; return; }
        perform('Start on boot', `${shellQuote(BIN)} ${enabled ? `enable-on-boot ${port}` : 'disable-on-boot'}`);
    });
    $('notificationToggle').addEventListener('change', () => {
        perform('Connection alerts', `${shellQuote(BIN)} notify ${$('notificationToggle').checked ? 'on' : 'off'}`);
    });
    $('authToggle').addEventListener('change', () => {
        const opening = $('authToggle').checked;
        if (opening && !window.confirm('Allow any client that can reach ADB to connect without an RSA trust prompt? This may also affect USB ADB. It does not grant root access.')) {
            $('authToggle').checked = false;
            return;
        }
        perform('ADB authentication', `${shellQuote(BIN)} auth ${opening ? 'open' : 'secure'}`);
    });
    $('connectionDetails').addEventListener('toggle', () => {
        if ($('connectionDetails').open) refreshConnectionCommands();
    });
    $('refreshBindingsButton').addEventListener('click', refreshBindings);
    $('customTargetForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        const target = $('customTargetInput').value.trim();
        if (!target) return;
        try {
            await run(`${shellQuote(BIN)} bind ${shellQuote(target)}`);
            $('customTargetInput').value = '';
            toast(`${target} allowed.`);
            await refreshAll();
            await refreshBindings();
        } catch (error) { toast(error.message || 'Could not add the network limit.'); }
    });
    $('refreshKeysButton').addEventListener('click', refreshKeys);
    $('importKeyButton').addEventListener('click', () => {
        $('keyPathInput').value = '';
        $('importError').hidden = true;
        $('importDialog').hidden = false;
        $('keyPathInput').focus();
    });
    $('cancelImportButton').addEventListener('click', () => { $('importDialog').hidden = true; });
    $('confirmImportButton').addEventListener('click', async () => {
        const path = $('keyPathInput').value.trim();
        if (!path) {
            $('importError').textContent = 'Enter the path to an ADB public key.';
            $('importError').hidden = false;
            return;
        }
        try {
            const result = await run(`${shellQuote(BIN)} --import-key ${shellQuote(path)}`);
            $('importDialog').hidden = true;
            showToastResult('Key import', result);
            await refreshKeys();
        } catch (error) {
            $('importError').textContent = error.message || 'Could not import the key.';
            $('importError').hidden = false;
        }
    });
    $('clearKeysButton').addEventListener('click', async () => {
        if (!window.confirm('Revoke every authorized ADB key? Existing trusted computers will need to be approved again.')) return;
        try {
            await run(`${shellQuote(BIN)} --clear-keys`);
            toast('All authorized keys revoked.');
            await refreshKeys();
        } catch (error) { toast(error.message || 'Could not revoke keys.'); }
    });
    $('importDialog').addEventListener('click', (event) => {
        if (event.target === $('importDialog')) $('importDialog').hidden = true;
    });
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') $('importDialog').hidden = true;
    });

    async function initialize() {
        if (!(await checkEnvironment())) return;
        await Promise.all([refreshAll(), refreshBindings(), refreshKeys()]);
        pollTimer = setInterval(() => { if (!busy) refreshAll(); }, 6000);
    }

    initialize();
})();
