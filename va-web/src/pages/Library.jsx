import { useEffect, useState } from 'react';
import {
  Button, Card, Empty, Form, Image, Input, Modal, Popconfirm, Space, Table, Tag, Upload, message,
} from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import api from '../api';

export default function Library() {
  const [bizno, setBizno] = useState(localStorage.getItem('va_bizno') || '');
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();
  const [createFiles, setCreateFiles] = useState([]);

  useEffect(() => {
    if (bizno.trim()) refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function refresh() {
    if (!bizno.trim()) return message.warning('请先填写项目编号 bizno');
    setLoading(true);
    try {
      setData(await api.libraryList(bizno.trim()));
    } catch (e) {
      setData(null);
      message.error({ content: `[${e.code}] ${e.message}`, duration: 5 });
    } finally {
      setLoading(false);
    }
  }

  async function removeRole(name) {
    try {
      await api.libraryDelete(bizno.trim(), name);
      message.success(`角色「${name}」已删除`);
      refresh();
    } catch (e) {
      message.error(`[${e.code}] ${e.message}`);
    }
  }

  async function submitCreate() {
    const values = await form.validateFields();
    if (createFiles.length === 0) return message.warning('请至少选择一张标准图');
    try {
      await api.libraryCreate(bizno.trim(), values.name, createFiles, values.model);
      message.success(`角色「${values.name}」已入库`);
      setCreateOpen(false);
      form.resetFields();
      setCreateFiles([]);
      refresh();
    } catch (e) {
      message.error({ content: `[${e.code}] ${e.message}`, duration: 5 });
    }
  }

  const roles = data?.ips || [];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card title="IP 标准库管理" size="small"
        extra={(
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={refresh}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)} disabled={!data}>添加角色</Button>
          </Space>
        )}
      >
        <Input.Search
          addonBefore="项目编号 bizno"
          placeholder="输入后回车加载角色列表"
          enterButton="加载"
          value={bizno}
          onChange={(e) => { setBizno(e.target.value); localStorage.setItem('va_bizno', e.target.value); }}
          onSearch={refresh}
        />
        {data && (
          <div style={{ marginTop: 8, color: '#64748b', fontSize: 12 }}>
            默认模型 {data.defaults?.model}，匹配阈值 match≥{data.defaults?.match_min} / mismatch≤{data.defaults?.mismatch_max}；
            标准图质量直接影响区分度，请用主体占满画面的干净图。
          </div>
        )}
      </Card>

      <Card size="small">
        {roles.length === 0 ? (
          <Empty description={data ? '该项目暂无角色，先添加角色建库' : '输入 bizno 加载角色列表'} />
        ) : (
          <Table
            size="small"
            rowKey="name"
            loading={loading}
            dataSource={roles}
            pagination={false}
            columns={[
              { title: '角色名称', dataIndex: 'name', width: 180 },
              { title: '标准图数', dataIndex: 'image_count', width: 90 },
              {
                title: '标准图',
                dataIndex: 'images',
                render: (images) => (
                  <Image.PreviewGroup>
                    <Space wrap>
                      {(images || []).slice(0, 8).map((img) => (
                        <RoleImage key={img.image_id} bizno={data.bizno} image={img} />
                      ))}
                      {images?.length > 8 && <Tag>+{images.length - 8}</Tag>}
                    </Space>
                  </Image.PreviewGroup>
                ),
              },
              {
                title: '操作',
                width: 90,
                render: (_, record) => (
                  <Popconfirm
                    title={`删除角色「${record.name}」？`}
                    description="将连同全部标准图与向量一起删除"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => removeRole(record.name)}
                  >
                    <Button danger size="small">删除</Button>
                  </Popconfirm>
                ),
              },
            ]}
            expandable={{
              expandedRowRender: (record) => (
                <div style={{ fontSize: 12, color: '#64748b' }}>
                  模型：{(record.images || []).flatMap((i) => i.models || []).filter((m, idx, arr) => arr.indexOf(m) === idx).join('、') || '－'}
                </div>
              ),
            }}
          />
        )}
      </Card>

      <Modal
        title={`添加角色（bizno: ${bizno}）`}
        open={createOpen}
        onOk={submitCreate}
        onCancel={() => setCreateOpen(false)}
        okText="入库"
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="角色名称（即标准答案名称）" rules={[{ required: true, message: '必填' }]}>
            <Input placeholder="如：闪电熊" maxLength={100} />
          </Form.Item>
          <Form.Item name="model" label="embedding 模型" initialValue="dinov2-base">
            <Input placeholder="dinov2-base / siglip-base" />
          </Form.Item>
          <Form.Item label="标准形象图（可多选）" required>
            <Upload.Dragger
              multiple
              accept="image/png,image/jpeg,image/webp,image/bmp"
              beforeUpload={(_, fileList) => { setCreateFiles([...fileList]); return false; }}
              onRemove={(f) => setCreateFiles((prev) => prev.filter((x) => x.uid !== f.uid))}
              fileList={createFiles}
            >
              点击或拖拽上传，主体占满画面的贴身干净图效果最好
            </Upload.Dragger>
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}

function RoleImage({ bizno, image }) {
  const [src, setSrc] = useState(null);
  useEffect(() => {
    let url = null;
    api.libraryImageSrc(bizno, image.image_id)
      .then((s) => { url = s; setSrc(s); })
      .catch(() => setSrc(null));
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [bizno, image.image_id]);
  return src
    ? <Image src={src} width={64} height={64} style={{ objectFit: 'cover', borderRadius: 6 }} />
    : <div style={{ width: 64, height: 64, background: '#f1f5f9', borderRadius: 6 }} />;
}
