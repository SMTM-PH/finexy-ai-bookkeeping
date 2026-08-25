const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('finexyDesktop', {
    getServerUrl: () => ipcRenderer.invoke('desktop:get-server-url'),
    setServerUrl: value => ipcRenderer.invoke('desktop:set-server-url', value),
    retry: () => ipcRenderer.invoke('desktop:retry'),
    quit: () => ipcRenderer.invoke('desktop:quit')
});
