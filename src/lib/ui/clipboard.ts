/** Copy only after a user action; support self-hosted HTTP pages as well as HTTPS. */
export async function writeClipboardText(value: string): Promise<boolean> {
    if (window.isSecureContext && navigator.clipboard?.writeText) {
        try {
            await navigator.clipboard.writeText(value);
            return true;
        } catch {
            // A denied clipboard permission may still allow copying selected text.
        }
    }

    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const selection = window.getSelection();
    const ranges = selection ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange()) : [];
    const input = document.createElement('textarea');
    input.value = value;
    input.readOnly = true;
    input.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;pointer-events:none';
    document.body.appendChild(input);
    try {
        input.focus({ preventScroll: true });
        input.select();
        input.setSelectionRange(0, value.length);
        return document.execCommand('copy');
    } catch {
        return false;
    } finally {
        input.value = '';
        input.remove();
        previousFocus?.focus({ preventScroll: true });
        if (selection) {
            selection.removeAllRanges();
            ranges.forEach(range => selection.addRange(range));
        }
    }
}
