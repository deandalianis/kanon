export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {})
    }
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(parseErrorMessage(response, text));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

function parseErrorMessage(response: Response, payload: string) {
  if (!payload) {
    return `${response.status} ${response.statusText}`;
  }

  try {
    const parsed = JSON.parse(payload) as {
      message?: string;
      detail?: string;
      error?: string;
    };

    if (parsed.message?.trim()) {
      return parsed.message.trim();
    }

    if (parsed.detail?.trim()) {
      return parsed.detail.trim();
    }

    if (parsed.error?.trim()) {
      return `${response.status} ${parsed.error.trim()}`;
    }
  } catch {
    // Fall back to the raw payload when the backend does not return JSON.
  }

  return payload;
}

export function asJson(body: unknown): RequestInit {
  return {
    method: "POST",
    body: JSON.stringify(body)
  };
}

export function asPut(body: unknown): RequestInit {
  return {
    method: "PUT",
    body: JSON.stringify(body)
  };
}
