import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { http } from '../lib/http'

// FE-03 test support: records every request the typed clients push through the
// shared axios instance and answers with a canned response, so the tests pin
// the transport contract (method + gateway-relative path + query + body + the
// unwrapped data return) without a network. Mirrors the adapter-stubbing
// technique lib/http.test.ts already uses.

export interface RecordedRequest {
  method: string
  url: string
  params?: Record<string, unknown>
  body?: unknown
}

export type Responder = (request: RecordedRequest) => [number, unknown]

function record(config: InternalAxiosRequestConfig): RecordedRequest {
  const raw = config.data
  return {
    method: (config.method ?? 'get').toUpperCase(),
    url: config.url ?? '',
    params: config.params as Record<string, unknown> | undefined,
    body:
      typeof raw === 'string' && raw.length > 0 ? JSON.parse(raw) : undefined,
  }
}

export function installRecordingAdapter(
  responder: Responder = () => [200, {}],
): RecordedRequest[] {
  const requests: RecordedRequest[] = []
  http.defaults.adapter = async (config) => {
    const recorded = record(config)
    requests.push(recorded)
    const [status, data] = responder(recorded)
    const response: AxiosResponse = {
      status,
      statusText: '',
      headers: {},
      config,
      data,
    }
    return response
  }
  return requests
}
