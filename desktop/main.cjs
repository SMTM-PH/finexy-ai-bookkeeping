const { app, BrowserWindow, Menu, ipcMain, net, shell } = require('electron');
const { spawn, execFile } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_SERVER_URL = 'http://127.0.0.1:8080';
const WINDOW_STATE_FILE = 'window-state.json';
const SETTINGS_FILE = 'desktop-settings.json';
const WINDOWS_STYLE_FILE = path.join(__dirname, 'windows.css');

let mainWindow;
let serverUrl = DEFAULT_SERVER_URL;

function readJson(fileName, fallback) {
    try {
        return JSON.parse(fs.readFileSync(path.join(app.getPath('userData'), fileName), 'utf8'));
    } catch {
        return fallback;
    }
}

function writeJson(fileName, value) {
    try {
        fs.mkdirSync(app.getPath('userData'), { recursive: true });
        fs.writeFileSync(path.join(app.getPath('userData'), fileName), JSON.stringify(value, null, 2));
    } catch {
        // The desktop client remains usable even if window state cannot be persisted.
    }
}

function normalizeServerUrl(value) {
    const candidate = String(value || '').trim();
    if (!candidate) return DEFAULT_SERVER_URL;
    try {
        const parsed = new URL(candidate.includes('://') ? candidate : `http://${candidate}`);
        if (!['http:', 'https:'].includes(parsed.protocol)) return DEFAULT_SERVER_URL;
        return parsed.origin;
    } catch {
        return DEFAULT_SERVER_URL;
    }
}

async function isServerReady() {
    try {
        const response = await net.fetch(`${serverUrl}/healthz.json`, {
            method: 'GET',
            cache: 'no-store',
            signal: AbortSignal.timeout(2500)
        });
        if (!response.ok) return false;
        const body = await response.json();
        return body?.success === true;
    } catch {
        return false;
    }
}

function startDetached(executable) {
    try {
        const child = spawn(executable, [], { detached: true, stdio: 'ignore', windowsHide: true });
        child.unref();
        return true;
    } catch {
        return false;
    }
}

function startDockerDesktop() {
    if (process.platform !== 'win32') return false;
    const candidates = [
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'DockerDesktop', 'Docker Desktop.exe'),
        path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Docker', 'Docker', 'Docker Desktop.exe')
    ];
    const executable = candidates.find(candidate => candidate && fs.existsSync(candidate));
    return executable ? startDetached(executable) : false;
}

function startExistingContainers() {
    return new Promise(resolve => {
        execFile('docker', ['start', 'ai-bookkeeping-ocr-1', 'ai-bookkeeping-bookkeeping-1'], {
            windowsHide: true,
            timeout: 12000
        }, error => resolve(!error));
    });
}

async function waitForServer({ bootstrap = true } = {}) {
    if (await isServerReady()) return true;

    if (bootstrap && serverUrl === DEFAULT_SERVER_URL) {
        await startExistingContainers();
        if (!(await isServerReady())) startDockerDesktop();
    }

    const attempts = bootstrap ? 20 : 4;
    for (let index = 0; index < attempts; index += 1) {
        await new Promise(resolve => setTimeout(resolve, 1500));
        if (bootstrap && index === 5) await startExistingContainers();
        if (await isServerReady()) return true;
    }
    return false;
}

function saveWindowState() {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    writeJson(WINDOW_STATE_FILE, {
        bounds: mainWindow.isMaximized() ? mainWindow.getNormalBounds() : mainWindow.getBounds(),
        maximized: mainWindow.isMaximized()
    });
}

async function loadApplication({ bootstrap = true } = {}) {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.setProgressBar(2, { mode: 'indeterminate' });
    const ready = await waitForServer({ bootstrap });
    mainWindow.setProgressBar(-1);
    if (!mainWindow || mainWindow.isDestroyed()) return;

    if (ready) {
        await mainWindow.loadURL(`${serverUrl}/desktop#/`);
    } else {
        await mainWindow.loadFile(path.join(__dirname, 'offline.html'));
    }
}

function createWindow() {
    const state = readJson(WINDOW_STATE_FILE, {});
    const bounds = state.bounds || { width: 1440, height: 900 };
    const options = {
        ...bounds,
        minWidth: 1160,
        minHeight: 720,
        title: 'Finexy',
        show: false,
        autoHideMenuBar: true,
        backgroundColor: '#F1F2F6',
        webPreferences: {
            preload: path.join(__dirname, 'preload.cjs'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true
        }
    };

    if (process.platform === 'win32') options.backgroundMaterial = 'mica';
    mainWindow = new BrowserWindow(options);
    Menu.setApplicationMenu(null);
    mainWindow.setMenuBarVisibility(false);
    if (state.maximized) mainWindow.maximize();

    mainWindow.once('ready-to-show', () => mainWindow.show());
    mainWindow.on('close', saveWindowState);
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        if (url.startsWith(serverUrl)) return { action: 'allow' };
        void shell.openExternal(url);
        return { action: 'deny' };
    });
    mainWindow.webContents.on('will-navigate', (event, url) => {
        if (url.startsWith(serverUrl) || url.startsWith('file:')) return;
        event.preventDefault();
        void shell.openExternal(url);
    });
    mainWindow.webContents.on('page-title-updated', event => {
        event.preventDefault();
        mainWindow.setTitle('Finexy');
    });
    mainWindow.webContents.on('did-finish-load', () => {
        if (process.platform !== 'win32' || !mainWindow.webContents.getURL().startsWith(serverUrl)) return;
        try {
            const windowsStyles = fs.readFileSync(WINDOWS_STYLE_FILE, 'utf8');
            void mainWindow.webContents.insertCSS(windowsStyles, { cssOrigin: 'user' });
        } catch {
            // The web client remains usable if the optional Windows layout layer cannot load.
        }
    });

    void loadApplication();
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (!mainWindow) return;
        if (mainWindow.isMinimized()) mainWindow.restore();
        mainWindow.show();
        mainWindow.focus();
    });

    app.whenReady().then(() => {
        app.setAppUserModelId('com.finexy.desktop');
        const settings = readJson(SETTINGS_FILE, {});
        serverUrl = normalizeServerUrl(process.env.FINEXY_SERVER_URL || settings.serverUrl);

        ipcMain.handle('desktop:get-server-url', () => serverUrl);
        ipcMain.handle('desktop:set-server-url', (_event, value) => {
            serverUrl = normalizeServerUrl(value);
            writeJson(SETTINGS_FILE, { serverUrl });
            return serverUrl;
        });
        ipcMain.handle('desktop:retry', () => loadApplication({ bootstrap: true }));
        ipcMain.handle('desktop:quit', () => app.quit());

        createWindow();
        app.on('activate', () => {
            if (BrowserWindow.getAllWindows().length === 0) createWindow();
        });
    });
}

app.on('window-all-closed', () => app.quit());
