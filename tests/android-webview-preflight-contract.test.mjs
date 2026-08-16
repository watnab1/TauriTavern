import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptPath = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    '..',
    'scripts',
    'android-webview-preflight.mjs',
);

const { parseFdSymlinks, parseProcNetUnix, selectWebViewDevtoolsSocketForPid } =
    await import(`${pathToFileURL(scriptPath).href}?test=${Date.now()}`);

const PROC_NET_UNIX = [
    'Num       RefCount Protocol Flags    Type St Inode Path',
    '0000000000000000: 00000002 00000000 00010000 0001 01 34567 /data/local/tmp/first',
    '0000000000000000: 00000002 00000000 00010000 0001 01 45678 @webview_devtools_remote_100',
    '0000000000000000: 00000002 00000000 00010000 0001 01 45679 @webview_devtools_remote_100',
].join('\n');

test('proc/net/unix parser keeps inode and socket path', () => {
    const entries = parseProcNetUnix(PROC_NET_UNIX);
    assert.deepEqual(entries, [
        { inode: '34567', path: '/data/local/tmp/first' },
        { inode: '45678', path: '@webview_devtools_remote_100' },
        { inode: '45679', path: '@webview_devtools_remote_100' },
    ]);
});

test('fd parser extracts only socket inodes owned by the selected PID', () => {
    const entries = parseFdSymlinks(`
lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 42 -> /dev/null
lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 43 -> socket:[45678]
lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 44 -> socket:[99999]
`);
    assert.deepEqual(entries, [{ inode: '45678' }, { inode: '99999' }]);
});

test('socket selection matches the exact PID and never defaults to the first socket', () => {
    const fdList = [
        'lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 42 -> socket:[45679]',
    ].join('\n');

    assert.equal(
        selectWebViewDevtoolsSocketForPid(PROC_NET_UNIX, fdList, 1234),
        'webview_devtools_remote_100',
    );
});

test('zero candidates fail closed', () => {
    const fdList = 'lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 42 -> socket:[99999]\n';
    assert.throws(
        () => selectWebViewDevtoolsSocketForPid(PROC_NET_UNIX, fdList, 1234),
        /No webview_devtools_remote_\* socket found/,
    );
});

test('multiple candidates fail closed', () => {
    const fdList = [
        'lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 42 -> socket:[45678]',
        'lrwx------ 1 u0_a123 u0_a123 64 2026-08-16 12:00 43 -> socket:[45679]',
    ].join('\n');
    assert.throws(
        () => selectWebViewDevtoolsSocketForPid(PROC_NET_UNIX, fdList, 1234),
        /Expected exactly one webview_devtools_remote_\* socket/,
    );
});

test('non-integer PID fails closed', () => {
    assert.throws(
        () => selectWebViewDevtoolsSocketForPid(PROC_NET_UNIX, '', '123 456'),
        /not a single integer/,
    );
});
