import { useEffect, useRef, useState } from 'react';
import {
  Button, Card, Descriptions, Drawer, Empty, Space, Table, Tag, message,
} from 'antd';
import { ReloadOutlined, CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons';
import api from '../api';

const STATUS_COLOR = { pending: 'default', running: 'processing', done: 'success', error: 'error' };
const fmt = (iso) => (iso ? iso.replace('T', ' ').replace(/Z$/, ' UTC') : '－');
const short = (id) => (id ? `${id.slice(0, 8)}…` : '');

export default function Tasks() {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState(null); // 完整任务对象
  const timerRef = useRef(null);

  async function refresh(silent = false) {
    if (!silent) setLoading(true);
    try {
      const data = await api.listTasks(100);
      setRows(data.tasks || []);
    } catch (e) {
      if (!silent) message.error(`[${e.code}] ${e.message}`);
    } finally {
      if (!silent) setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
    timerRef.current = setInterval(() => refresh(true), 5000);
    return () => clearInterval(timerRef.current);
  }, []);

  async function openDetail(taskId) {
    try {
      setDetail(await api.getTask(taskId));
    } catch (e) {
      message.error(`[${e.code}] ${e.message}`);
    }
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="任务记录（SQLite 持久化，重启后已完成结果仍可查）"
        size="small"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => refresh()}>刷新</Button>}
      >
        {rows.length === 0 ? (
          <Empty description="暂无任务；在工作台发起一次 IP 一致性全检试试" />
        ) : (
          <Table
            size="small"
            rowKey="task_id"
            loading={loading}
            dataSource={rows}
            pagination={{ pageSize: 15, hideOnSinglePage: true }}
            onRow={(r) => ({ onClick: () => openDetail(r.task_id), style: { cursor: 'pointer' } })}
            columns={[
              { title: '任务', dataIndex: 'task_id', width: 120, render: (v) => <code>{short(v)}</code> },
              { title: '流水线', dataIndex: 'recipe', width: 170, render: (v) => <code style={{ fontSize: 12 }}>{v}</code> },
              { title: '状态', dataIndex: 'status', width: 90, render: (v) => <Tag color={STATUS_COLOR[v]}>{v}</Tag> },
              { title: '创建时间', dataIndex: 'created_at', width: 180, render: fmt },
              { title: '完成时间', dataIndex: 'finished_at', width: 180, render: fmt },
              { title: '回调', dataIndex: 'callback_attempts', width: 80, render: (v, r) => (v ? `${v} 次${r.callback_last_code ? ` / ${r.callback_last_code}` : ''}` : '－') },
              { title: '操作', width: 80, render: (_, r) => <a onClick={() => openDetail(r.task_id)}>详情</a> },
            ]}
          />
        )}
      </Card>

      <Drawer
        title={detail ? `任务 ${short(detail.task_id)}` : ''}
        width={560}
        open={!!detail}
        onClose={() => setDetail(null)}
      >
        {detail && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions size="small" column={2} bordered items={[
              { key: 's', label: '状态', children: <Tag color={STATUS_COLOR[detail.status]}>{detail.status}</Tag> },
              { key: 'p', label: 'bizno', children: detail.params?.bizno ?? '－' },
              { key: 'c', label: '创建', children: fmt(detail.created_at) },
              { key: 'f', label: '完成', children: fmt(detail.finished_at) },
            ]} />
            {detail.error && (
              <Descriptions size="small" column={1} bordered items={[
                { key: 'e', label: '错误', children: <span style={{ color: '#dc2626' }}>[{detail.error.code}] {detail.error.message}{detail.error.provider ? `（${detail.error.provider}）` : ''}</span> },
              ]} />
            )}
            <div>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>执行步骤</div>
              <Space direction="vertical" size={6} style={{ width: '100%' }}>
                {(detail.steps || []).map((s) => (
                  <div key={s.name} style={{ display: 'flex', gap: 8, fontSize: 13, background: '#f8fafc', padding: '6px 10px', borderRadius: 6 }}>
                    {s.status === 'ok'
                      ? <CheckCircleFilled style={{ color: '#16a34a' }} />
                      : <CloseCircleFilled style={{ color: '#dc2626' }} />}
                    <code style={{ fontSize: 12 }}>{s.name}</code>
                    <span style={{ color: '#94a3b8' }}>{s.detail}</span>
                    <span style={{ marginLeft: 'auto', color: '#94a3b8' }}>{s.elapsed_ms} ms</span>
                  </div>
                ))}
                {(detail.steps || []).length === 0 && <span style={{ color: '#94a3b8' }}>尚无步骤记录</span>}
              </Space>
            </div>
            {detail.result?.summary && (
              <Descriptions size="small" column={2} bordered title="结论" items={[
                { key: 'o', label: 'overall', children: <Tag color={detail.result.summary.overall === 'pass' ? 'success' : detail.result.summary.overall === 'fail' ? 'error' : 'warning'}>{detail.result.summary.overall}</Tag> },
                { key: 'r', label: '形象区域', children: detail.result.summary.regions_total },
                { key: 'v', label: 'match/mismatch/低置信', children: `${detail.result.summary.verdicts?.match}/${detail.result.summary.verdicts?.mismatch}/${detail.result.summary.verdicts?.low_confidence}` },
                { key: 't', label: 'OCR 文本框', children: detail.result.summary.ocr_texts_passed },
              ]} />
            )}
          </Space>
        )}
      </Drawer>
    </Space>
  );
}
