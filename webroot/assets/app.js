(() => {
    'use strict';

    const BIN = '/data/adb/modules/wadbd/system/bin/wadbd';
    const PERSIST = '/data/adb/wadbd';
    const BOOT_FLAG = `${PERSIST}/enable_on_boot`;
    const BIND_FILE = `${PERSIST}/bind_ifaces`;
    const $ = (id) => document.getElementById(id);
    let pollTimer = null;
    let toastTimer = null;
    let busy = false;
    let supported = false;
    let wirelessPort = '';
    let authSupported = false;
    let authMode = 'secure';
    let moduleVersion = '';

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

    function parseFlag(value) {
        return String(value || '').trim() === '1';
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
        const commands = [
            `getprop service.adb.tcp.port`,
            `[ -f ${shellQuote(BOOT_FLAG)} ] && cat ${shellQuote(BOOT_FLAG)} || true`,
            `[ -f ${shellQuote(PERSIST + '/notify_conn')} ] && cat ${shellQuote(PERSIST + '/notify_conn')} || echo 1`,
            `getprop init.svc.adbd`,
            `ss -Htn state established 2>/dev/null`,
            `${shellQuote(BIN)} auth status 2>/dev/null`,
            `sed -n 's/^version=//p' ${shellQuote('/data/adb/modules/wadbd/module.prop')} | head -n 1`
        ];
        const results = await Promise.all(commands.map(run));
        const port = results[0].stdout.trim();
        wirelessPort = /^\d+$/.test(port) && port !== '0' && port !== '-1' ? port : '';
        const bootPort = results[1].stdout.trim();
        const notifyEnabled = results[2].stdout.trim() !== '0';
        const daemon = results[3].stdout.trim();
        const clients = wirelessPort
            ? results[4].stdout.split(/\r?\n/).filter((line) => {
                const fields = line.trim().split(/\s+/);
                return fields.length >= 4 && fields[3].endsWith(`:${wirelessPort}`);
            }).length
            : 0;
        const authValues = Object.fromEntries(results[5].stdout.trim().split(/\r?\n/).map((line) => line.split('=').map((part) => part.trim())).filter((parts) => parts.length === 2));
        authMode = authValues.AUTH_MODE === 'open' ? 'open' : 'secure';
        authSupported = authValues.AUTH_SUPPORTED === '1';
        moduleVersion = results[6].stdout.trim();

        $('adbHeading').textContent = wirelessPort ? `Wireless ADB is on` : 'Wireless ADB is off';
        $('adbSummary').textContent = wirelessPort ? `Listening on port ${wirelessPort}.` : 'Turn it on to connect over Wi-Fi or another network.';
        $('wirelessToggle').checked = Boolean(wirelessPort);
        if (!document.activeElement || document.activeElement !== $('portInput')) $('portInput').value = wirelessPort || (bootPort && /^\d+$/.test(bootPort) ? bootPort : '5555');
        $('daemonState').textContent = daemon || 'unknown';
        $('clientState').textContent = `${clients} ${clients === 1 ? 'session' : 'sessions'}`;
        $('bootToggle').checked = Boolean(bootPort && /^\d+$/.test(bootPort));
        $('bootSummary').textContent = bootPort && /^\d+$/.test(bootPort) ? `Will start on port ${bootPort} after boot.` : 'Wireless ADB stays off after reboot.';
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
        $('versionLabel').textContent = moduleVersion ? `Version ${moduleVersion}` : 'Wireless ADB module';
        $('connectionSummary').textContent = wirelessPort ? `Port ${wirelessPort}` : 'Available when Wireless ADB is on';
        setControlsEnabled(true);
        if (wirelessPort && $('connectionDetails').open) await refreshConnectionCommands();
    }

    async function refreshConnectionCommands() {
        const host = $('connectionCommands');
        if (!wirelessPort) {
            host.replaceChildren(makeText('p', 'muted', 'Enable Wireless ADB to see commands for this device.'));
            return;
        }
        try {
            const [addressResult, bindingResult] = await Promise.all([
                run('ip -o -4 addr show scope global 2>/dev/null'),
                run(`[ -f ${shellQuote(BIND_FILE)} ] && cat ${shellQuote(BIND_FILE)} || true`)
            ]);
            const bindings = bindingResult.stdout.split(/\r?\n/).map((target) => target.trim()).filter(Boolean);
            const addresses = [];
            addressResult.stdout.split(/\r?\n/).forEach((line) => {
                const match = line.match(/^\s*\d+:\s+([^\s:]+)(?:@[^\s:]+)?:.*\binet\s+(\d+(?:\.\d+){3})\//);
                if (!match || match[1] === 'lo') return;
                const isAllowed = !bindings.length || bindings.some((target) => {
                    if (target.includes('/')) return ipv4InSubnet(match[2], target);
                    if (target.endsWith('+')) return match[1].startsWith(target.slice(0, -1));
                    return match[1] === target;
                });
                if (isAllowed) addresses.push({ iface: match[1], ip: match[2] });
            });
            host.replaceChildren();
            if (!addresses.length) {
                host.append(makeText('p', 'muted', bindings.length
                    ? 'No selected network has an active address. Check the connection limits below.'
                    : 'No network address is available yet. Connect to Wi-Fi or another network and refresh.'));
                return;
            }
            addresses.forEach(({ iface, ip }) => {
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
        } catch {
            host.replaceChildren(makeText('p', 'muted', 'Could not read network addresses. Refresh to try again.'));
        }
    }

    function ipv4InSubnet(address, cidr) {
        const [network, prefixText] = cidr.split('/');
        const prefix = Number(prefixText);
        const toNumber = (value) => {
            const octets = value.split('.').map(Number);
            if (octets.length !== 4 || octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255)) return null;
            return octets.reduce((result, octet) => ((result << 8) | octet) >>> 0, 0);
        };
        const addressNumber = toNumber(address);
        const networkNumber = toNumber(network);
        if (addressNumber === null || networkNumber === null || !Number.isInteger(prefix) || prefix < 0 || prefix > 32) return false;
        const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0;
        return (addressNumber & mask) === (networkNumber & mask);
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
            const [linkResult, bindResult] = await Promise.all([
                run('ip -o link show 2>/dev/null'),
                run(`[ -f ${shellQuote(BIND_FILE)} ] && cat ${shellQuote(BIND_FILE)} || true`)
            ]);
            const available = linkResult.stdout.split(/\r?\n/).map((line) => {
                const match = line.match(/^\s*\d+:\s+([^:]+):/);
                return match ? match[1].split('@')[0] : '';
            }).filter((name) => name && name !== 'lo');
            const bound = new Set(bindResult.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean));
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
            const { stdout } = await run('cat /data/misc/adb/adb_keys 2>/dev/null || true');
            const keys = stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line, id) => ({
                id,
                publicKey: line.split(/\s+/)[0]
            }));
            host.replaceChildren();
            if (!keys.length) {
                host.append(makeText('p', 'placeholder', 'No authorized keys found.'));
                return;
            }
            await Promise.all(keys.map(async (key) => { key.fingerprint = await androidKeyFingerprint(key.publicKey); }));
            keys.forEach((key) => {
                const row = document.createElement('div');
                row.className = 'key-row';
                const copy = document.createElement('span');
                copy.className = 'key-copy';
                copy.append(makeText('span', 'key-name', key.fingerprint || 'Fingerprint unavailable'));
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

    async function androidKeyFingerprint(encodedKey) {
        try {
            const decoded = atob(encodedKey);
            const key = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
            if (key.length !== 524 || new DataView(key.buffer).getUint32(0, true) !== 64) return '';

            const toDer = (tag, bytes) => {
                let length;
                if (bytes.length < 128) length = [bytes.length];
                else if (bytes.length < 256) length = [0x81, bytes.length];
                else length = [0x82, (bytes.length >>> 8) & 0xff, bytes.length & 0xff];
                return Uint8Array.from([tag, ...length, ...bytes]);
            };
            const join = (...parts) => Uint8Array.from(parts.flatMap((part) => Array.from(part)));

            let modulus = Array.from(key.slice(8, 264)).reverse();
            while (modulus.length > 1 && modulus[0] === 0) modulus.shift();
            if (modulus[0] & 0x80) modulus.unshift(0);
            let exponentValue = new DataView(key.buffer).getUint32(520, true);
            const exponent = [];
            while (exponentValue > 0) {
                exponent.unshift(exponentValue & 0xff);
                exponentValue = Math.floor(exponentValue / 256);
            }
            if (!exponent.length) return '';
            if (exponent[0] & 0x80) exponent.unshift(0);

            const rsaKey = toDer(0x30, join(toDer(0x02, modulus), toDer(0x02, exponent)));
            const algorithm = toDer(0x30, Uint8Array.from([
                0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
            ]));
            const subjectPublicKeyInfo = toDer(0x30, join(algorithm, toDer(0x03, join(Uint8Array.of(0), rsaKey))));
            const derBase64 = btoa(String.fromCharCode(...subjectPublicKeyInfo));
            const { stdout } = await run(`printf %s ${shellQuote(derBase64)} | base64 -d | sha256sum`);
            const digest = stdout.match(/[a-f0-9]{64}/i);
            return digest ? digest[0].toUpperCase() : '';
        } catch {
            return '';
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
        if ($('connectionDetails').open) await refreshConnectionCommands();
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
        pollTimer = setInterval(() => { if (!busy) refreshAll(); }, 8000);
    }

    initialize();
})();
