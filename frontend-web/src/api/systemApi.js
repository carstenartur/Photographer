import { request } from './auth';

export async function fetchSystemInfo() {
  return request('/system/info');
}
