// page-auth.jsx — Login, Register, Forgot password, Goodbye

function AuthShell({ title, subtitle, children, footer }) {
  return (
    <div style={{ minHeight: 'calc(100vh - var(--header-h))', display: 'grid', placeItems: 'center', padding: '40px 20px', background: 'var(--bg-subtle)' }}>
      <div style={{ width: '100%', maxWidth: 420 }}>
        <div className="card" style={{ padding: 32, background: 'var(--surface)' }}>
          <div style={{ marginBottom: 24 }}>
            <h1 style={{ fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em', margin: 0 }}>{title}</h1>
            <p style={{ fontSize: 14, color: 'var(--fg-muted)', marginTop: 6, marginBottom: 0 }}>{subtitle}</p>
          </div>
          {children}
        </div>
        {footer && <div style={{ textAlign: 'center', marginTop: 18, fontSize: 13, color: 'var(--fg-muted)' }}>{footer}</div>}
      </div>
    </div>
  );
}

function LoginPage({ setPage }) {
  const [email, setEmail] = React.useState('');
  const [pw, setPw] = React.useState('');
  return (
    <AuthShell title="Welcome back" subtitle="Sign in to your SLParcels account."
      footer={<>Don't have an account? <a onClick={() => setPage('register')} style={{ color: 'var(--brand)', fontWeight: 600, cursor: 'pointer' }}>Request membership</a></>}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div>
          <label className="field-label">Email</label>
          <input className="input input--lg" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
        </div>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label className="field-label" style={{ marginBottom: 0 }}>Password</label>
            <a onClick={() => setPage('forgot')} style={{ fontSize: 12, color: 'var(--brand)', cursor: 'pointer' }}>Forgot?</a>
          </div>
          <input type="password" className="input input--lg" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="••••••••" style={{ marginTop: 6 }} />
        </div>
        <Btn variant="primary" size="lg" block onClick={() => setPage('home')}>Sign in</Btn>
        <div style={{ fontSize: 12, color: 'var(--fg-subtle)', textAlign: 'center', marginTop: 4 }}>
          Signed in for 7 days on this device
        </div>
      </div>
    </AuthShell>
  );
}

function RegisterPage({ setPage }) {
  const [pw, setPw] = React.useState('');
  const [agree, setAgree] = React.useState(false);
  const strength = pw.length === 0 ? null : pw.length < 8 ? 'weak' : pw.length < 12 ? 'fair' : 'strong';
  const tone = strength === 'weak' ? 'var(--danger)' : strength === 'fair' ? 'var(--warning)' : 'var(--success)';
  return (
    <AuthShell title="Create your account" subtitle="Join the digital curator."
      footer={<>Already have an account? <a onClick={() => setPage('login')} style={{ color: 'var(--brand)', fontWeight: 600, cursor: 'pointer' }}>Sign in</a></>}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div><label className="field-label">Email</label><input className="input input--lg" placeholder="you@example.com" /></div>
        <div>
          <label className="field-label">Password</label>
          <input type="password" className="input input--lg" value={pw} onChange={(e) => setPw(e.target.value)} placeholder="At least 8 characters" />
          {strength && (
            <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{ flex: 1, height: 4, background: 'var(--bg-muted)', borderRadius: 2, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: strength === 'weak' ? '33%' : strength === 'fair' ? '66%' : '100%', background: tone, transition: 'width .2s' }} />
              </div>
              <span style={{ fontSize: 11.5, fontWeight: 600, color: tone, textTransform: 'capitalize' }}>{strength}</span>
            </div>
          )}
        </div>
        <div><label className="field-label">Confirm password</label><input type="password" className="input input--lg" placeholder="Re-enter password" /></div>
        <label style={{ display: 'flex', gap: 10, alignItems: 'flex-start', fontSize: 13, color: 'var(--fg-muted)', cursor: 'pointer' }}>
          <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} style={{ marginTop: 2 }} />
          <span>I agree to the <a onClick={(e) => { e.preventDefault(); setPage('terms'); }} style={{ color: 'var(--brand)', fontWeight: 500 }}>Terms of Service</a></span>
        </label>
        <Btn variant="primary" size="lg" block disabled={!agree} onClick={() => setPage('verify')}>Create account</Btn>
      </div>
    </AuthShell>
  );
}

function ForgotPasswordPage({ setPage }) {
  const [sent, setSent] = React.useState(false);
  return (
    <AuthShell title={sent ? 'Check your email' : 'Forgot your password?'} subtitle={sent ? 'A reset link is on its way.' : "Enter your email and we'll send you a reset link."}
      footer={<a onClick={() => setPage('login')} style={{ color: 'var(--fg-muted)', cursor: 'pointer' }}>← Back to sign in</a>}>
      {sent ? (
        <div style={{ textAlign: 'center' }}>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success)', display: 'grid', placeItems: 'center', margin: '0 auto 16px' }}>
            <Icons.Check size={28} />
          </div>
          <Badge tone="warning">[STUB] Backend reset endpoint not yet implemented</Badge>
          <p style={{ fontSize: 13.5, color: 'var(--fg-muted)', marginTop: 16 }}>If an account exists for that email, you'll receive a reset link within a few minutes.</p>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div><label className="field-label">Email</label><input className="input input--lg" placeholder="you@example.com" /></div>
          <Btn variant="primary" size="lg" block onClick={() => setSent(true)}>Send reset link</Btn>
        </div>
      )}
    </AuthShell>
  );
}

function GoodbyePage({ setPage }) {
  return (
    <AuthShell title="Account deleted" subtitle="We're sorry to see you go.">
      <div style={{ textAlign: 'center' }}>
        <div style={{ width: 64, height: 64, borderRadius: '50%', background: 'var(--bg-muted)', color: 'var(--fg-muted)', display: 'grid', placeItems: 'center', margin: '0 auto 16px' }}>
          <Icons.User size={28} />
        </div>
        <p style={{ fontSize: 13.5, color: 'var(--fg-muted)', lineHeight: 1.55 }}>
          Your account has been permanently removed. Your prior auctions, bids, and reviews remain visible attributed to <span className="bold">"Deleted user"</span> as a non-erasable historical record.
        </p>
        <Btn variant="primary" size="lg" block onClick={() => setPage('register')} style={{ marginTop: 18 }}>Register a new account</Btn>
      </div>
    </AuthShell>
  );
}

window.LoginPage = LoginPage;
window.RegisterPage = RegisterPage;
window.ForgotPasswordPage = ForgotPasswordPage;
window.GoodbyePage = GoodbyePage;
