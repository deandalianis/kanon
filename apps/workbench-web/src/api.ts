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
    throw new Error(text || `${response.status} ${response.statusText}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
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
