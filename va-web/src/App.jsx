import { useState } from 'react';
import { Layout, Menu } from 'antd';
import { ExperimentOutlined, DatabaseOutlined, SettingOutlined, UnorderedListOutlined } from '@ant-design/icons';
import Workbench from './pages/Workbench';
import Library from './pages/Library';
import Settings from './pages/Settings';
import Tasks from './pages/Tasks';

export default function App() {
  const [page, setPage] = useState('workbench');
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider theme="dark" width={208}>
        <div style={{ color: '#fff', padding: '18px 16px 10px', fontWeight: 700, fontSize: 16 }}>
          Vision Assistant
        </div>
        <div style={{ color: '#64748b', padding: '0 16px 12px', fontSize: 12 }}>包装视觉能力控制台</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[page]}
          onClick={({ key }) => setPage(key)}
          items={[
            { key: 'workbench', icon: <ExperimentOutlined />, label: '检测工作台' },
            { key: 'library', icon: <DatabaseOutlined />, label: '标准库管理' },
            { key: 'tasks', icon: <UnorderedListOutlined />, label: '任务记录' },
            { key: 'settings', icon: <SettingOutlined />, label: '设置' },
          ]}
        />
      </Layout.Sider>
      <Layout.Content style={{ padding: 24, overflow: 'auto', background: '#f1f5f9' }}>
        {page === 'workbench' && <Workbench />}
        {page === 'library' && <Library />}
        {page === 'tasks' && <Tasks />}
        {page === 'settings' && <Settings />}
      </Layout.Content>
    </Layout>
  );
}
