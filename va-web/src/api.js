// VA API 封装：统一 X-API-Key、统一错误体 {"error":{code,message}} 解析
function buildHeaders(extra = {}) {
  const headers = { ...extra };
  const key = localStorage.getItem('va_key') || '';
  if (key) headers['X-API-Key'] = key;
  return headers;
}

async function request(path, options = {}) {
  let resp;
  try {
    resp = await fetch(path, { ...options, headers: buildHeaders(options.headers) });
  } catch (err) {
    throw { code: 'NETWORK_ERROR', message: '网络错误: ' + err.message };
  }
  if (resp.ok) {
    const contentType = resp.headers.get('content-type') || '';
    return contentType.includes('application/json') ? resp.json() : resp.blob();
  }
  let error = { code: `HTTP_${resp.status}`, message: resp.statusText || '请求失败' };
  try {
    const body = await resp.json();
    if (body && body.error) error = body.error;
  } catch {
    /* 非 JSON 错误体，保留默认 */
  }
  throw error;
}

export const api = {
  health: () => request('/api/v1/health'),

  ocrAnalyze: ({ file, url, options }) => {
    const fd = new FormData();
    if (file) fd.append('image', file);
    if (url) fd.append('url', url);
    if (options) fd.append('options', JSON.stringify(options));
    return request('/api/v1/ocr/analyze', { method: 'POST', body: fd });
  },

  ipIdentify: ({ file, url, bizno, model, includeCrops }) => {
    const fd = new FormData();
    if (file) fd.append('image', file);
    if (url) fd.append('url', url);
    fd.append('bizno', bizno);
    if (model) fd.append('model', model);
    if (includeCrops) fd.append('includeCrops', 'true');
    return request('/api/v1/ip/identify', { method: 'POST', body: fd });
  },

  // 异步全检：返回 {task_id}，轮询 getTask 直至 done/error
  ipIdentifyAsync: ({ file, url, bizno, model, callbackUrl }) => {
    const fd = new FormData();
    if (file) fd.append('image', file);
    if (url) fd.append('url', url);
    fd.append('bizno', bizno);
    fd.append('async', 'true');
    if (model) fd.append('model', model);
    if (callbackUrl) fd.append('callbackUrl', callbackUrl);
    return request('/api/v1/ip/identify', { method: 'POST', body: fd });
  },

  getTask: (taskId) => request(`/api/v1/tasks/${taskId}`),

  listTasks: (limit = 50) => request(`/api/v1/tasks?limit=${limit}`),

  libraryList: (bizno) =>
    request(`/api/v1/ip/library?bizno=${encodeURIComponent(bizno)}`),

  libraryCreate: (bizno, name, files, model) => {
    const fd = new FormData();
    fd.append('bizno', bizno);
    fd.append('name', name);
    if (model) fd.append('model', model);
    for (const file of files) fd.append('files', file);
    return request('/api/v1/ip/library', { method: 'POST', body: fd });
  },

  libraryDelete: (bizno, name) =>
    request(
      `/api/v1/ip/library/${encodeURIComponent(name)}?bizno=${encodeURIComponent(bizno)}`,
      { method: 'DELETE' }
    ),

  // 返回可直接用于 <img src> 的 blob URL
  libraryImageSrc: async (bizno, imageId) => {
    const blob = await request(
      `/api/v1/ip/library/images/${encodeURIComponent(imageId)}?bizno=${encodeURIComponent(bizno)}`
    );
    return URL.createObjectURL(blob);
  },
};

export default api;
