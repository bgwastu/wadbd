
// ─── Key Management ────────────────────────────────────────────────

async function updateKeyList() {
    const keyListDiv = document.getElementById('keyList');
    if (!isEnvironmentSupported) return;

    try {
        const { stdout, errno } = await exec('/system/bin/wadbd --list-keys');
        if (errno !== 0 || !stdout) {
            keyListDiv.innerHTML = '<div class="keyst-empty">No keys found or error reading keys.</div>';
            return;
        }

        const lines = stdout.trim().split('\n').filter(l => l.match(/\[[0-9]+\]/));
        if (lines.length === 0) {
            keyListDiv.innerHTML = '<div class="keyst-empty">No authorized keys.</div>';
            return;
        }

        let html = '';
        for (const line of lines) {
            const idMatch = line.match(/\[([0-9]+)\]/);
            const id = idMatch ? idMatch[1] : '?';
            const cleanLine = line.replace(/^\s*\[(\d+)\]\s*/, '');
            const hashMatch = line.match(/hash:\s*([a-f0-9]+)/);
            const hash = hashMatch ? hashMatch[1] : '';
            const comment = cleanLine.replace(/\s*\(hash:\s*[a-f0-9]+\)\s*$/, '').trim();

            html += `
                <div class="keyst-item">
                    <span class="key-id">${id}</span>
                    <span class="key-comment">${comment}</span>
                    <span class="key-hash">${hash}</span>
                    <button class="remove-key-btn" onclick="removeKey(${id})" title="Revoke this key">✕</button>
                </div>`;
        }
        keyListDiv.innerHTML = html;
    } catch {
        keyListDiv.innerHTML = '<div class="keyst-empty">Failed to load keys.</div>';
    }
}

async function removeKey(id) {
    if (!confirm(`Revoke access for key [${id}]? This device will need to re-authorize.`)) return;
    try {
        const { errno } = await exec(`/system/bin/wadbd --remove-key ${id}`);
        if (errno === 0) {
            alert(`Key [${id}] revoked.`);
            updateKeyList();
            // Restart adbd so the change takes effect
            await exec('stop adbd; sleep 1; start adbd');
        } else {
            alert(`Failed to remove key [${id}].`);
        }
    } catch {
        alert('Error removing key.');
    }
}

async function clearAllKeys() {
    if (!confirm('Revoke ALL authorized keys?\n\nEvery device will need the trust dialog again.')) return;
    try {
        const { errno } = await exec('/system/bin/wadbd --clear-keys');
        if (errno === 0) {
            alert('All keys revoked.');
            updateKeyList();
        } else {
            alert('Failed to clear keys.');
        }
    } catch {
        alert('Error clearing keys.');
    }
}

async function importKeyPrompt() {
    const path = prompt('Enter the path to the adbkey.pub file on the device:\n\n(e.g. /sdcard/Download/adbkey.pub)');
    if (!path) return;
    try {
        const { stdout, errno } = await exec(`/system/bin/wadbd --import-key ${path}`);
        if (errno === 0) {
            alert(stdout || 'Key imported successfully.');
            updateKeyList();
        } else {
            alert(stdout || 'Failed to import key.');
        }
    } catch {
        alert('Error importing key.');
    }
}

// Wire up buttons
document.addEventListener('DOMContentLoaded', function() {
    const refreshBtn = document.getElementById('refreshKeysBtn');
    const clearBtn = document.getElementById('clearAllKeysBtn');
    const importBtn = document.getElementById('importKeyBtn');
    if (refreshBtn) refreshBtn.addEventListener('click', updateKeyList);
    if (clearBtn) clearBtn.addEventListener('click', clearAllKeys);
    if (importBtn) importBtn.addEventListener('click', importKeyPrompt);
});
