import { useEffect, useState } from 'react';
import { Alert, Button, Card, Descriptions, Input, Space, Tag, message } from 'antd';
import api from '../api';

export default function Settings() {
  const [key, setKey] = useState(localStorage.getItem('va_key') || '');
  const [health, setHealth] = useState(null);
  const [error, setError] = useState(null);

  const loadHealth = () => {
    setError(null);
    api.health()
      .then(setHealth)
      .catch((e) => { setHealth(null); setError(e); });
  };

  useEffect(loadHealth, []);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title="连接与鉴权" size="small"
        extra={<Button onClick={loadHealth}>重新检测</Button>}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div style={{ maxWidth: 480 }}>
            <div style={{ marginBottom: 4, color: '#475569', fontSize: 13 }}>API Key（保存在浏览器本地）</div>
            <Space.Compact style={{ width: '100%' }}>
              <Input.Password value={key} onChange={(e) => setKey(e.target.value)} placeholder="服务端开启鉴权时必填" />
              <Button
                type="primary"
                onClick={() => { localStorage.setItem('va_key', key.trim()); message.success('已保存'); }}
              >
                保存
              </Button>
            </Space.Compact>
          </div>
          {error && <Alert type="error" showIcon message={`无法连接 va-server：[${error.code}] ${error.message}`} />}
          {health && (
            <>
              {health.authRequired && !(localStorage.getItem('va_key') || '') && (
                <Alert type="warning" showIcon message="服务端已开启鉴权，请先填写并保存 API Key，否则业务请求将返回 401" />
              )}
              <Descriptions size="small" column={2} bordered items={[
                { key: 's', label: '服务', children: `${health.service} v${health.version}` },
                { key: 'a', label: '鉴权', children: health.authRequired ? <Tag color="green">已开启</Tag> : <Tag color="orange">关闭（开发模式）</Tag> },
                { key: 'o', label: 'OCR 服务', children: health.capabilities?.ocr?.ok ? <Tag color="green">在线</Tag> : <Tag color="red">不可达</Tag> },
                { key: 'i', label: 'IP 检测服务', children: health.capabilities?.ipcheck?.ok ? <Tag color="green">在线</Tag> : <Tag color="red">不可达</Tag> },
              ]} />
              {(!health.capabilities?.ipcheck?.ok) && (
                <Alert
                  type="info"
                  showIcon
                  message="IP 检测服务不在线时，标准库管理与一致性全检不可用；OCR 功能不受影响。"
                  description="启动方式（packvision 机器上）：uvicorn ip_check_api:app --host 0.0.0.0 --port 8001"
                />
              )}
            </>
          )}
        </Space>
      </Card>
    </Space>
  );
}
