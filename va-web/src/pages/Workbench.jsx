import { useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Card, Col, Descriptions, Input, Radio, Row, Space, Spin, Table, Tag, Upload, message,
} from 'antd';
import { ScanOutlined, SafetyCertificateOutlined, InboxOutlined, CheckCircleFilled, CloseCircleFilled, LoadingOutlined } from '@ant-design/icons';
import api from '../api';
import OverlayCanvas from '../components/OverlayCanvas';

const OVERALL = {
  pass: { color: 'success', text: '通过' },
  review: { color: 'warning', text: '需人工复核' },
  fail: { color: 'error', text: '不通过' },
};
const TYPE_COLOR = { text: 'blue', art_text: 'purple', qr: 'cyan', barcode: 'cyan' };
const VERDICT_COLOR = { match: 'green', mismatch: 'red', low_confidence: 'orange' };

export default function Workbench() {
  const [mode, setMode] = useState('file');
  const [file, setFile] = useState(null);
  const [remoteUrl, setRemoteUrl] = useState('');
  const [bizno, setBizno] = useState(localStorage.getItem('va_bizno') || '');
  const [loading, setLoading] = useState(null);
  const [ocrResult, setOcrResult] = useState(null);
  const [ipResult, setIpResult] = useState(null);
  const [task, setTask] = useState(null); // 轮询中的异步任务
  const timerRef = useRef(null);

  useEffect(() => () => { if (timerRef.current) clearInterval(timerRef.current); }, []);

  const activeUrl = mode === 'file' ? (file ? URL.createObjectURL(file) : '') : remoteUrl;
  const hasImage = mode === 'file' ? !!file : !!remoteUrl.trim();

  const saveBizno = (v) => {
    setBizno(v);
    localStorage.setItem('va_bizno', v);
  };

  async function runOcr() {
    if (!hasImage) return message.warning('请先提供图片');
    setLoading('ocr');
    setOcrResult(null);
    setIpResult(null);
    try {
      const result = await api.ocrAnalyze({ file: mode === 'file' ? file : null, url: mode === 'url' ? remoteUrl : null });
      setOcrResult(result);
      message.success(`OCR 完成：识别 ${result.items?.length ?? 0} 项`);
    } catch (e) {
      message.error({ content: `[${e.code}] ${e.message}`, duration: 5 });
    } finally {
      setLoading(null);
    }
  }

  async function runIp() {
    if (!hasImage) return message.warning('请先提供图片');
    if (!bizno.trim()) return message.warning('IP 一致性检测需要填写项目编号 bizno');
    setLoading('ip');
    setOcrResult(null);
    setIpResult(null);
    setTask(null);
    try {
      // 异步任务：大图全检 100~200s，提交后轮询进度（步骤实时持久化在服务端）
      const submitted = await api.ipIdentifyAsync({ file: mode === 'file' ? file : null, url: mode === 'url' ? remoteUrl : null, bizno: bizno.trim() });
      pollTask(submitted.task_id);
    } catch (e) {
      setLoading(null);
      message.error({ content: `[${e.code}] ${e.message}`, duration: 6 });
    }
  }

  function pollTask(taskId) {
    timerRef.current = setInterval(async () => {
      let t;
      try {
        t = await api.getTask(taskId);
      } catch (e) {
        stopPoll();
        message.error({ content: `[${e.code}] ${e.message}`, duration: 6 });
        return;
      }
      setTask(t);
      if (t.status === 'done') {
        stopPoll();
        setIpResult(t.result);
      } else if (t.status === 'error') {
        stopPoll();
        const e = t.error || {};
        message.error({ content: `[${e.code || 'ERROR'}] ${e.message || '任务失败'}${e.provider ? `（下游: ${e.provider}）` : ''}`, duration: 8 });
      }
    }, 2000);
  }

  function stopPoll() {
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = null;
    setLoading(null);
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title="图片输入" size="small">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)} options={[{ value: 'file', label: '上传文件' }, { value: 'url', label: '图片 URL' }]} />
          {mode === 'file' ? (
            <Upload.Dragger
              accept="image/png,image/jpeg,image/webp,image/bmp"
              maxCount={1}
              beforeUpload={(f) => { setFile(f); return false; }}
              onRemove={() => setFile(null)}
              fileList={file ? [{ uid: '-1', name: file.name, status: 'done' }] : []}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽包装图到此处</p>
              <p className="ant-upload-hint">PNG / JPG / WEBP / BMP，单张 ≤200MB</p>
            </Upload.Dragger>
          ) : (
            <Input placeholder="http://内网图床/package.png" value={remoteUrl} onChange={(e) => setRemoteUrl(e.target.value)} />
          )}
          <Row gutter={12}>
            <Col flex="auto">
              <Input addonBefore="bizno（IP 标准库项目编号）" value={bizno} onChange={(e) => saveBizno(e.target.value)} placeholder="如 customer-a（仅 IP 检测需要）" />
            </Col>
          </Row>
          <Space>
            <Button type="primary" icon={<ScanOutlined />} loading={loading === 'ocr'} onClick={runOcr}>OCR 识别</Button>
            <Button type="primary" ghost icon={<SafetyCertificateOutlined />} loading={loading === 'ip'} onClick={runIp}>IP 一致性全检</Button>
          </Space>
        </Space>
      </Card>

      {loading === 'ip' && (
        <Card title="任务执行中" size="small" extra={task && <Tag color="blue">{task.status}</Tag>}>
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            {(task?.steps || []).map((s) => (
              <div key={s.name} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
                {s.status === 'ok'
                  ? <CheckCircleFilled style={{ color: '#16a34a' }} />
                  : <CloseCircleFilled style={{ color: '#dc2626' }} />}
                <span style={{ fontFamily: 'monospace' }}>{s.name}</span>
                <span style={{ color: '#94a3b8' }}>{s.detail}</span>
                <span style={{ marginLeft: 'auto', color: '#94a3b8' }}>{s.elapsed_ms} ms</span>
              </div>
            ))}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: '#64748b' }}>
              <LoadingOutlined />
              {task?.status === 'running' ? '步骤执行中…（大图 OCR 可达 1~3 分钟）' : '任务排队中…'}
            </div>
          </Space>
        </Card>
      )}

      {loading === 'ocr' && (
        <Card size="small">
          <Spin tip="OCR 识别中（大图可能 1~3 分钟）…">
            <div style={{ height: 60 }} />
          </Spin>
        </Card>
      )}

      {ipResult && (
        <Card
          title="一致性全检报告"
          size="small"
          extra={<Tag color={OVERALL[ipResult.summary.overall]?.color} style={{ fontSize: 14 }}>{OVERALL[ipResult.summary.overall]?.text}</Tag>}
        >
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions size="small" column={4} bordered items={[
              { key: '1', label: '形象区域', children: ipResult.summary.regions_total },
              { key: '2', label: '已配名称', children: ipResult.summary.regions_paired },
              { key: '3', label: 'match / mismatch / 低置信', children: `${ipResult.summary.verdicts.match} / ${ipResult.summary.verdicts.mismatch} / ${ipResult.summary.verdicts.low_confidence}` },
              { key: '4', label: 'OCR 文本框', children: ipResult.summary.ocr_texts_passed },
            ]} />
            {(ipResult.summary.name_inconsistent?.length > 0 || ipResult.summary.unpaired_names?.length > 0) && (
              <Alert
                type="warning"
                showIcon
                message="存在需关注项"
                description={(
                  <div>
                    {ipResult.summary.name_inconsistent?.length > 0 && (
                      <div>图文错位区域：{ipResult.summary.name_inconsistent.map((n) => `${n.printed_name}→${n.role_name}`).join('、')}</div>
                    )}
                    {ipResult.summary.unpaired_names?.length > 0 && (
                      <div>未配对名称候选：{ipResult.summary.unpaired_names.join('、')}</div>
                    )}
                  </div>
                )}
              />
            )}
            <OverlayCanvas
              imageUrl={activeUrl}
              imageMeta={ipResult.image_meta}
              ocr={ipResult.ocr}
              ip={ipResult.ip}
            />
            <Table
              size="small"
              rowKey={(_, i) => i}
              pagination={false}
              dataSource={ipResult.ip?.regions || []}
              columns={[
                { title: '印刷名称', dataIndex: 'printed_name', render: (v) => v ?? '－' },
                { title: '归因角色', dataIndex: 'matched_role', render: (v) => v ?? '－' },
                { title: '识别角色', dataIndex: 'role_name', render: (v) => v ?? '－' },
                { title: '相似度', dataIndex: 'similarity', render: (v) => (v ?? 0).toFixed(3) },
                { title: '判定', dataIndex: 'verdict', render: (v) => <Tag color={VERDICT_COLOR[v]}>{v}</Tag> },
                { title: '图文一致', dataIndex: 'name_consistent', render: (v) => (v === null || v === undefined ? '－' : v ? '✓' : '✗') },
              ]}
            />
          </Space>
        </Card>
      )}

      {ocrResult && !ipResult && (
        <Card title={`OCR 结果（${ocrResult.items?.length ?? 0} 项）`} size="small">
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Descriptions size="small" column={3} bordered items={[
              { key: 'w', label: '原图', children: `${ocrResult.image_meta?.width}×${ocrResult.image_meta?.height}` },
              { key: 't', label: '切片数', children: ocrResult.image_meta?.tile_count ?? '－' },
              { key: 'c', label: '裁白边', children: ocrResult.image_meta?.crop ? ocrResult.image_meta.crop.join(', ') : '无' },
            ]} />
            <OverlayCanvas imageUrl={activeUrl} imageMeta={ocrResult.image_meta} ocr={ocrResult} ip={{ regions: [] }} />
            <Table
              size="small"
              rowKey={(_, i) => i}
              pagination={{ pageSize: 10, hideOnSinglePage: true }}
              dataSource={ocrResult.items || []}
              columns={[
                { title: '类型', dataIndex: 'type', width: 90, render: (v) => <Tag color={TYPE_COLOR[v]}>{v}</Tag> },
                { title: '内容', render: (_, it) => it.text ?? it.content ?? '－' },
                { title: '置信度', dataIndex: 'confidence', width: 90, render: (v) => (v ?? 0).toFixed(2) },
                { title: 'bbox（像素）', dataIndex: 'bbox', width: 220, render: (v) => (v ?? []).map((n) => Math.round(n)).join(', ') },
                { title: '来源', dataIndex: 'source', width: 110 },
              ]}
            />
          </Space>
        </Card>
      )}
    </Space>
  );
}
