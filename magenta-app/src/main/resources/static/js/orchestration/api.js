export async function jsonFetch(url, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    if (options.body && !headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
    }
    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
        let message = `${response.status} ${response.statusText}`;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (_ignored) {
            const text = await response.text();
            if (text) message = text;
        }
        throw new Error(message);
    }
    if (response.status === 204) {
        return null;
    }
    return response.json();
}

export async function consumeSse(response, handlers = {}) {
    const text = await response.text();
    const events = parseSse(text);
    for (const event of events) {
        const handler = handlers[event.event] || handlers.message;
        if (handler) {
            handler(event.data, event);
        }
    }
    return events;
}

export function parseSse(text) {
    return text.split(/\n\n+/)
        .map(block => {
            const event = { event: "message", data: "" };
            for (const line of block.split(/\n/)) {
                if (line.startsWith("event:")) {
                    event.event = line.slice(6).trim();
                } else if (line.startsWith("data:")) {
                    event.data += line.slice(5).trim();
                }
            }
            if (!event.data) return null;
            try {
                event.data = JSON.parse(event.data);
            } catch (_ignored) {
            }
            return event;
        })
        .filter(Boolean);
}

export function renderError(target, error) {
    const node = typeof target === "string" ? document.querySelector(target) : target;
    if (node) {
        node.textContent = error && error.message ? error.message : String(error);
    }
}
