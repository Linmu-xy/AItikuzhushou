import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { AssistantEffort } from './AssistantWorkspace';
import { errorText, maintenanceApi } from './maintenanceApi';

export type AccountProfile = {
  id: string; username: string; displayName?: string; email?: string; emailVerified?: boolean;
  phoneNumber?: string; phoneVerified?: boolean; avatarUrl?: string;
  assistantEffort?: AssistantEffort; assistantWebSearch?: boolean;
  roles: string[]; status: string; dailyQuota: number; sessionRevision?: number; createdAt?: string;
};
type ProfileSettingsProps = {
  auth: string;
  profile: AccountProfile;
  summary?: { knowledgeBases: number; documents: number; storageUsedBytes: number; storageQuotaBytes: number };
  quota?: { requests: number; remaining: number; dailyQuota: number };
  onProfileUpdated: (profile: AccountProfile) => void;
  onOpenKnowledge: () => void;
  onOpenQuota: () => void;
};
const sections = [{ id: 'identity', label: '个人资料' }, { id: 'preferences', label: '助手偏好' }, { id: 'security', label: '账户安全' }] as const;
type Section = typeof sections[number]['id'];
type Feedback = { action: string; message: string; error: boolean };
const efforts: { value: AssistantEffort; label: string; description: string }[] = [
  { value: 'FAST', label: '快速', description: '日常提问，及时响应' },
  { value: 'STANDARD', label: '标准', description: '兼顾速度与思考深度' },
  { value: 'DEEP', label: '深入', description: '复杂问题，充分推敲' },
];
const formatBytes = (value: number) => value >= 1024 ** 3 ? (value / 1024 ** 3).toFixed(1) + ' GB'
  : value >= 1024 ** 2 ? (value / 1024 ** 2).toFixed(1) + ' MB' : value > 0 ? Math.ceil(value / 1024) + ' KB' : '0 KB';

function ProfileIcon({ kind }: { kind: Section | 'check' | 'arrow' | 'eye' | 'eye-off' }) {
  return <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === 'identity' && <><circle cx="12" cy="8" r="3.5" /><path d="M5 21v-2a7 7 0 0 1 14 0v2" /></>}
    {kind === 'preferences' && <><path d="M4 7h9m4 0h3M4 17h3m4 0h9" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></>}
    {kind === 'security' && <><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2" /></>}
    {kind === 'check' && <path d="m5 12 4 4L19 6" />}
    {kind === 'arrow' && <path d="m9 5 7 7-7 7" />}
    {(kind === 'eye' || kind === 'eye-off') && <><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" />{kind === 'eye-off' && <path d="m3 3 18 18" />}</>}
  </svg>;
}

export function ProfileAvatar({ profile, className = '' }: { profile: AccountProfile; className?: string }) {
  const [failedUrl, setFailedUrl] = useState<string>();
  const initial = Array.from(profile.displayName?.trim() || profile.username || '题')[0];
  return <span className={'profile-avatar ' + className}>
    {profile.avatarUrl && failedUrl !== profile.avatarUrl
      ? <img key={profile.avatarUrl} src={profile.avatarUrl} alt="" onError={() => setFailedUrl(profile.avatarUrl)} /> : initial}
  </span>;
}

function PasswordField({ label, value, onChange, isNew = false }: { label: string; value: string; onChange: (value: string) => void; isNew?: boolean }) {
  const [visible, setVisible] = useState(false);
  return <label className="profile-field">{label}<span className="profile-password-input">
    <input aria-label={label} type={visible ? 'text' : 'password'} required minLength={isNew ? 8 : undefined} value={value} onChange={event => onChange(event.target.value)} autoComplete={isNew ? 'new-password' : 'current-password'} placeholder={isNew ? '至少 8 位' : '输入当前密码'} />
    <button type="button" className="profile-eye" aria-label={(visible ? '隐藏' : '显示') + label} aria-pressed={visible} onClick={() => setVisible(current => !current)}><ProfileIcon kind={visible ? 'eye-off' : 'eye'} /></button>
  </span></label>;
}

export function ProfileSettings({ auth, profile, summary, quota, onProfileUpdated, onOpenKnowledge, onOpenQuota }: ProfileSettingsProps) {
  const [section, setSection] = useState<Section>('identity');
  const [username, setUsername] = useState(profile.username);
  const [displayName, setDisplayName] = useState(profile.displayName || profile.username);
  const [phone, setPhone] = useState(profile.phoneNumber || '');
  const [phonePassword, setPhonePassword] = useState('');
  const [email, setEmail] = useState(profile.email || '');
  const [emailCode, setEmailCode] = useState('');
  const [emailCooldown, setEmailCooldown] = useState(0);
  const [emailCodeSending, setEmailCodeSending] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [effort, setEffort] = useState<AssistantEffort>(profile.assistantEffort || 'STANDARD');
  const [webSearch, setWebSearch] = useState(Boolean(profile.assistantWebSearch));
  const [avatarFile, setAvatarFile] = useState<File>();
  const [avatarPreview, setAvatarPreview] = useState('');
  const [busy, setBusy] = useState('');
  const busyRef = useRef(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const [feedback, setFeedback] = useState<Feedback>();

  // Sync only fields changed on the server, preserving drafts in other sections.
  useEffect(() => setUsername(profile.username), [profile.username]);
  useEffect(() => setDisplayName(profile.displayName || profile.username), [profile.displayName, profile.username]);
  useEffect(() => setPhone(profile.phoneNumber || ''), [profile.phoneNumber]);
  useEffect(() => setEmail(profile.email || ''), [profile.email]);
  useEffect(() => setEffort(profile.assistantEffort || 'STANDARD'), [profile.assistantEffort]);
  useEffect(() => setWebSearch(Boolean(profile.assistantWebSearch)), [profile.assistantWebSearch]);
  useEffect(() => {
    if (!emailCooldown) return;
    const timer = window.setTimeout(() => setEmailCooldown(value => Math.max(0, value - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [emailCooldown]);
  useEffect(() => () => { if (avatarPreview) URL.revokeObjectURL(avatarPreview); }, [avatarPreview]);

  const act = async (action: string, work: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(action); setFeedback(undefined);
    try { await work(); }
    catch (cause) { setFeedback({ action, message: errorText(cause), error: true }); }
    finally { busyRef.current = false; setBusy(''); }
  };
  const updated = (next: AccountProfile, action: string, message: string) => {
    onProfileUpdated(next);
    setFeedback({ action, message, error: false });
  };
  const response = (action: string) => feedback?.action === action
    ? <p className={'profile-feedback' + (feedback.error ? ' error' : '')} role={feedback.error ? 'alert' : 'status'}>{!feedback.error && <ProfileIcon kind="check" />}{feedback.message}</p> : null;
  const saveUsername = (event: FormEvent) => {
    event.preventDefault();
    void act('username', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile', auth, { method: 'PUT', body: JSON.stringify({ username: username.trim() }) });
      updated(next, 'username', '登录用户名已更新，当前登录保持有效。');
    });
  };
  const saveDisplayName = (event: FormEvent) => {
    event.preventDefault();
    void act('display-name', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile/display-name', auth, { method: 'PUT', body: JSON.stringify({ displayName: displayName.trim() }) });
      setDisplayName(next.displayName || next.username);
      updated(next, 'display-name', '个人资料已保存。');
    });
  };
  const saveSettings = (event: FormEvent) => {
    event.preventDefault();
    void act('settings', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile/settings', auth, { method: 'PUT', body: JSON.stringify({ assistantEffort: effort, assistantWebSearch: webSearch }) });
      updated(next, 'settings', '助手偏好已保存。');
    });
  };
  const requestEmailCode = async () => {
    if (emailCodeSending || emailCooldown > 0 || !email.trim()) return;
    setEmailCodeSending(true); setFeedback(undefined);
    try {
      const result = await maintenanceApi<{ delivery: string; debugCode?: string }>('/api/auth/profile/email-code', auth, { method: 'POST', body: JSON.stringify({ email: email.trim() }) });
      setEmailCooldown(60);
      setFeedback({ action: 'email', error: false, message: result.debugCode ? '开发环境验证码：' + result.debugCode + '（10 分钟内有效）' : '验证码已发送到新邮箱，请在 10 分钟内输入。' });
    } catch (cause) { setFeedback({ action: 'email', error: true, message: errorText(cause) }); }
    finally { setEmailCodeSending(false); }
  };
  const changeEmail = (event: FormEvent) => {
    event.preventDefault();
    void act('email', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile/email', auth, { method: 'PUT', body: JSON.stringify({ email: email.trim(), code: emailCode.trim() }) });
      setEmailCode(''); updated(next, 'email', '邮箱已验证并更新，其他登录会话已失效。');
    });
  };
  const changePhone = (event: FormEvent) => {
    event.preventDefault();
    void act('phone', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile/phone', auth, { method: 'PUT', body: JSON.stringify({ phoneNumber: phone.trim(), currentPassword: phonePassword }) });
      setPhonePassword(''); updated(next, 'phone', phone.trim() ? '手机号已保存，当前状态为未验证。' : '手机号已移除。');
    });
  };
  const changePassword = (event: FormEvent) => {
    event.preventDefault();
    if (newPassword !== confirmPassword) { setFeedback({ action: 'password', message: '两次输入的新密码不一致。', error: true }); return; }
    if (new TextEncoder().encode(newPassword).length > 72) { setFeedback({ action: 'password', message: '密码过长，请控制在 72 个英文字符或 24 个汉字以内。', error: true }); return; }
    void act('password', async () => {
      const next = await maintenanceApi<AccountProfile>('/api/auth/profile/password', auth, { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) });
      setCurrentPassword(''); setNewPassword(''); setConfirmPassword(''); updated(next, 'password', '密码已更新，其他设备需重新登录。');
    });
  };
  const selectAvatar = (file?: File) => {
    if (!file) return;
    setFeedback(undefined);
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size === 0 || file.size > 2 * 1024 * 1024) {
      setFeedback({ action: 'avatar', message: '请选择不超过 2 MB 的 PNG 或 JPEG 图片。', error: true }); return;
    }
    setAvatarFile(file); setAvatarPreview(URL.createObjectURL(file));
  };
  const cancelAvatar = () => { setAvatarFile(undefined); setAvatarPreview(''); };
  const uploadAvatar = () => {
    if (!avatarFile) return;
    void act('avatar', async () => {
      const form = new FormData(); form.append('file', avatarFile);
      const next = await maintenanceApi<AccountProfile>('/api/auth/avatar', auth, { method: 'POST', body: form });
      cancelAvatar(); updated(next, 'avatar', '头像已更新。');
    });
  };
  const removeAvatar = () => void act('avatar', async () => {
    const next = await maintenanceApi<AccountProfile>('/api/auth/avatar', auth, { method: 'DELETE' });
    cancelAvatar(); updated(next, 'avatar', '已恢复默认头像。');
  });

  const nameDirty = displayName.trim() !== (profile.displayName || profile.username);
  const settingsDirty = effort !== (profile.assistantEffort || 'STANDARD') || webSearch !== Boolean(profile.assistantWebSearch);
  const role = profile.roles.map(value => ({ ADMIN: '管理员', EDITOR: '编辑用户', VIEWER: '只读用户' })[value] || value).join('、');
  const storagePercent = summary && summary.storageQuotaBytes > 0 ? Math.min(100, Math.max(0, summary.storageUsedBytes / summary.storageQuotaBytes * 100)) : 0;

  return <div className="profile-settings">
    <header className="profile-heading"><h2>个人中心</h2><p>让工作台更适合你的使用习惯。</p></header>
    <div className="profile-layout">
      <aside className="profile-overview" aria-label="账户概览">
        <div className="profile-person"><ProfileAvatar profile={profile} className="large" /><h3>{profile.displayName || profile.username}</h3><p>{role || '用户'}</p><span className={'profile-account-state' + (profile.status === 'ACTIVE' ? ' active' : '')}>{profile.status === 'ACTIVE' ? '账户正常' : '账户已停用'}</span></div>
        <section className="profile-resource"><div className="profile-resource-title"><h3>知识库空间</h3><button type="button" className="profile-link" onClick={onOpenKnowledge}>管理<ProfileIcon kind="arrow" /></button></div><p>{summary ? summary.knowledgeBases + ' 个知识库，' + summary.documents + ' 份文档' : '正在加载空间信息…'}</p><progress max="100" value={storagePercent} aria-label="知识库存储使用比例" /><span className="profile-resource-value">{summary ? formatBytes(summary.storageUsedBytes) + ' / ' + formatBytes(summary.storageQuotaBytes) : '—'}</span></section>
        <section className="profile-resource"><div className="profile-resource-title"><h3>今日模型额度</h3><button type="button" className="profile-link" onClick={onOpenQuota}>详情<ProfileIcon kind="arrow" /></button></div><p className="profile-quota-value">{quota ? <><strong>{quota.remaining.toLocaleString('zh-CN')}</strong><span>次可用</span></> : '正在加载额度…'}</p><span className="profile-resource-value">{quota ? '已用 ' + quota.requests.toLocaleString('zh-CN') + ' / ' + quota.dailyQuota.toLocaleString('zh-CN') + ' 次' : '—'}</span></section>
        <details className="profile-account-details"><summary>账户信息</summary><dl><dt>创建时间</dt><dd>{profile.createdAt ? new Date(profile.createdAt).toLocaleDateString('zh-CN') : '—'}</dd><dt>用户编号</dt><dd>{profile.id}</dd></dl></details>
      </aside>
      <div className="profile-editor">
        <div className="profile-tabs" role="tablist" aria-label="个人中心设置">
          {sections.map((item, index) => <button type="button" role="tab" key={item.id} id={'profile-tab-' + item.id} aria-controls={'profile-panel-' + item.id} aria-selected={section === item.id} tabIndex={section === item.id ? 0 : -1} onClick={() => setSection(item.id)} onKeyDown={event => {
            const nextIndex = event.key === 'ArrowRight' ? (index + 1) % sections.length : event.key === 'ArrowLeft' ? (index + sections.length - 1) % sections.length : event.key === 'Home' ? 0 : event.key === 'End' ? sections.length - 1 : -1;
            if (nextIndex < 0) return;
            event.preventDefault(); setSection(sections[nextIndex].id); document.getElementById('profile-tab-' + sections[nextIndex].id)?.focus();
          }}><ProfileIcon kind={item.id} />{item.label}</button>)}
        </div>
        <section className="profile-tab-panel" role="tabpanel" id="profile-panel-identity" aria-labelledby="profile-tab-identity" hidden={section !== 'identity'}>
          <div className="profile-section-heading"><h3>你的个人资料</h3><p>设置你在工作台中显示的头像和名称。</p></div>
          <div className="profile-avatar-editor">
            {avatarPreview ? <span className="profile-avatar large"><img src={avatarPreview} alt="待保存的头像预览" /></span> : <ProfileAvatar profile={profile} className="large" />}
            <div className="profile-avatar-controls"><div className="profile-inline-actions"><button type="button" className="secondary" disabled={Boolean(busy)} onClick={() => fileInput.current?.click()}>{avatarFile ? '重新选择' : '更换头像'}</button>{profile.avatarUrl && !avatarFile && <button type="button" className="profile-link" disabled={Boolean(busy)} onClick={removeAvatar}>恢复默认</button>}</div><p>PNG 或 JPEG，最大 2 MB，自动居中裁剪。</p><input ref={fileInput} type="file" accept="image/png,image/jpeg" hidden aria-label="选择头像图片" onChange={event => { selectAvatar(event.target.files?.[0]); event.target.value = ''; }} disabled={Boolean(busy)} /></div>
          </div>
          {avatarFile && <div className="profile-avatar-pending"><span title={avatarFile.name}>{avatarFile.name}</span><div className="profile-inline-actions"><button type="button" disabled={Boolean(busy)} onClick={uploadAvatar}>{busy === 'avatar' ? '保存中…' : '保存头像'}</button><button type="button" className="profile-link" disabled={Boolean(busy)} onClick={cancelAvatar}>取消</button></div></div>}
          {response('avatar')}
          <form className="profile-edit-form" onSubmit={saveDisplayName}><fieldset disabled={Boolean(busy)}><label className="profile-field">显示名称<input value={displayName} maxLength={40} onChange={event => setDisplayName(event.target.value)} autoComplete="nickname" placeholder="填写你希望显示的名称" /><small>最多 40 个字符，留空时使用登录用户名。</small></label><div className="profile-form-footer"><span>{nameDirty ? '有尚未保存的修改' : '修改后点击保存'}</span><button disabled={!nameDirty}>{busy === 'display-name' ? '保存中…' : '保存资料'}</button></div></fieldset>{response('display-name')}</form>
          <div className="profile-context-note"><ProfileIcon kind="security" /><span>登录用户名、密码和联系方式可在<button type="button" className="profile-link" onClick={() => setSection('security')}>账户安全</button>中管理。</span></div>
        </section>
        <section className="profile-tab-panel" role="tabpanel" id="profile-panel-preferences" aria-labelledby="profile-tab-preferences" hidden={section !== 'preferences'}>
          <div className="profile-section-heading"><h3>按你的节奏思考</h3><p>设置助手的默认行为，对话和出题时仍可单独调整。</p></div>
          <form className="profile-preferences-form" onSubmit={saveSettings}><fieldset disabled={Boolean(busy)}><fieldset className="profile-effort-group"><legend>默认思考强度</legend><div className="profile-effort-options">{efforts.map(item => <label key={item.value} className={'profile-effort-option' + (effort === item.value ? ' selected' : '')}><input type="radio" name="profile-effort" value={item.value} checked={effort === item.value} onChange={() => setEffort(item.value)} /><b>{item.label}</b><span>{item.description}</span></label>)}</div></fieldset>
            <label className="profile-search-toggle"><span><b>允许联网搜索</b><small>需要补充或核实公开信息时，允许助手搜索网络。</small></span><input type="checkbox" role="switch" aria-label="允许联网搜索" checked={webSearch} onChange={event => setWebSearch(event.target.checked)} /></label>
            <div className="profile-form-footer"><span>{settingsDirty ? '有尚未保存的修改' : '已使用当前默认设置'}</span><button disabled={!settingsDirty}>{busy === 'settings' ? '保存中…' : '保存偏好'}</button></div></fieldset>{response('settings')}</form>
        </section>
        <section className="profile-tab-panel" role="tabpanel" id="profile-panel-security" aria-labelledby="profile-tab-security" hidden={section !== 'security'}>
          <div className="profile-section-heading"><h3>账户安全</h3><p>管理登录方式，保护你的个人工作空间。</p></div>
          <details className="profile-security-item"><summary><span><b>登录用户名</b><small>{profile.username}</small></span><span className="profile-disclosure-action">修改<ProfileIcon kind="arrow" /></span></summary><form onSubmit={saveUsername}><fieldset disabled={Boolean(busy)}><label className="profile-field">新用户名<input value={username} minLength={2} maxLength={32} onChange={event => setUsername(event.target.value)} autoComplete="username" required /><small>2–32 位中文、字母、数字或 ._-，修改后其他设备需重新登录。</small></label><button disabled={username.trim().length < 2 || username.trim() === profile.username}>{busy === 'username' ? '保存中…' : '更新用户名'}</button></fieldset>{response('username')}</form></details>
          <details className="profile-security-item"><summary><span><b>登录密码</b><small>定期更换密码，保护账户安全</small></span><span className="profile-disclosure-action">修改<ProfileIcon kind="arrow" /></span></summary><form onSubmit={changePassword}><fieldset disabled={Boolean(busy)}><PasswordField label="当前密码" value={currentPassword} onChange={setCurrentPassword} /><PasswordField label="新密码" value={newPassword} onChange={setNewPassword} isNew /><PasswordField label="确认新密码" value={confirmPassword} onChange={setConfirmPassword} isNew /><p className="profile-help">更新后当前登录保持有效，其他设备需重新登录。</p><button disabled={!currentPassword || newPassword.length < 8 || !confirmPassword}>{busy === 'password' ? '更新中…' : '更新密码'}</button></fieldset>{response('password')}</form></details>
          <details className="profile-security-item"><summary><span><b>邮箱</b><small>{profile.email || '尚未绑定'}{profile.email ? (profile.emailVerified ? '（已验证）' : '（未验证）') : ''}</small></span><span className="profile-disclosure-action">管理<ProfileIcon kind="arrow" /></span></summary><form onSubmit={changeEmail}><label className="profile-field">新邮箱<input type="email" required value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" placeholder="name@example.com" /></label><label className="profile-field">邮箱验证码<span className="profile-code-row"><input required inputMode="numeric" autoComplete="one-time-code" value={emailCode} onChange={event => setEmailCode(event.target.value.replace(/\D/g, '').slice(0, 6))} placeholder="6 位验证码" /><button type="button" className="secondary" onClick={() => void requestEmailCode()} disabled={Boolean(busy) || emailCodeSending || emailCooldown > 0 || !email.trim() || email.trim().toLowerCase() === (profile.email || '').toLowerCase()}>{emailCodeSending ? '正在发送…' : emailCooldown ? emailCooldown + ' 秒后重发' : '发送验证码'}</button></span></label><button disabled={Boolean(busy) || email.length < 5 || emailCode.length !== 6 || email.trim().toLowerCase() === (profile.email || '').toLowerCase()}>{busy === 'email' ? '验证并更新…' : '验证并更新邮箱'}</button>{response('email')}</form></details>
          <details className="profile-security-item"><summary><span><b>手机号</b><small>{profile.phoneNumber || '尚未绑定'}{profile.phoneNumber ? (profile.phoneVerified ? '（已验证）' : '（未验证）') : ''}</small></span><span className="profile-disclosure-action">管理<ProfileIcon kind="arrow" /></span></summary><form onSubmit={changePhone}><label className="profile-field">手机号<input type="tel" value={phone} onChange={event => setPhone(event.target.value)} autoComplete="tel" placeholder="例如 +86 138 0000 0000" /></label><label className="profile-field">当前密码<input type="password" required value={phonePassword} onChange={event => setPhonePassword(event.target.value)} autoComplete="current-password" /></label><p className="profile-help">当前未接入短信服务，号码会标记为未验证，不能用于登录或找回密码。</p><button disabled={Boolean(busy) || !phonePassword || phone.trim() === (profile.phoneNumber || '')}>{busy === 'phone' ? '保存中…' : phone.trim() ? '保存手机号' : '移除手机号'}</button>{response('phone')}</form></details>
        </section>
      </div>
    </div>
  </div>;
}
