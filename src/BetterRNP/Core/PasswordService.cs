namespace BetterRNP.Core;

public sealed class PasswordService
{
    // Used only to identify and remove the obsolete built-in password from old installations.
    private const string LegacyBuiltInPassword =
        "#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}";

    private readonly ConfigService _config;
    private readonly EncryptionService _encryption;
    private readonly LogService _log;
    private bool _unlocked;
    private int _ctrlLCount;
    private DateTime _lastCtrlL = DateTime.MinValue;

    public PasswordService(ConfigService config, EncryptionService encryption, LogService log)
    {
        _config = config;
        _encryption = encryption;
        _log = log;
        RemoveLegacyDefaultPassword();
        _unlocked = !HasPassword;
    }

    public bool HasPassword => !string.IsNullOrWhiteSpace(_config.PasswordHash);

    public bool IsLocked => HasPassword && !_unlocked;

    public bool RecoveryShortcutEnabled => _config.RecoveryShortcutEnabled;

    public bool CanConfigureRecoveryShortcut => HasPassword && !IsLocked;

    public bool VerifyAndUnlock(string password)
    {
        if (!HasPassword)
        {
            _unlocked = true;
            return true;
        }

        var hash = _config.PasswordHash;
        if (string.IsNullOrWhiteSpace(hash) || !_encryption.VerifyPassword(password, hash))
        {
            _log.Write(LogLevel.Warn, "密码验证失败", "PASSWORD_FAILED");
            return false;
        }

        _unlocked = true;
        if (_encryption.NeedsPasswordUpgrade(hash))
        {
            _config.SetPasswordHash(_encryption.HashPassword(password));
            _log.Write("密码散列已升级为 PBKDF2-HMAC-SHA256", "SECURITY_UPGRADE");
        }
        _log.Write("密码验证通过，配置已解锁", "PASSWORD_UNLOCK");
        return true;
    }

    public bool SetPassword(string newPassword)
    {
        if (newPassword.Length < 4 || newPassword.Length > 100)
        {
            _log.Write(LogLevel.Warn, $"长度={newPassword.Length}", "密码修改被拒绝");
            return false;
        }
        if (HasPassword && IsLocked)
        {
            _log.Write(LogLevel.Warn, "未鉴权修改密码", "操作被拒绝");
            return false;
        }

        var hadPassword = HasPassword;
        _config.SetPasswordHash(_encryption.HashPassword(newPassword));
        _unlocked = true;
        _log.Write(hadPassword ? "密码已修改" : "密码已设置", "PASSWORD_CHANGED");
        return true;
    }

    public bool ChangePassword(string newPassword) => SetPassword(newPassword);

    public void Lock()
    {
        if (!HasPassword)
        {
            _unlocked = true;
            return;
        }
        _unlocked = false;
        _log.Write("配置已锁定", "PASSWORD_LOCK");
    }

    public void Unlock()
    {
        if (HasPassword)
        {
            _unlocked = true;
        }
    }

    public bool SetRecoveryShortcutEnabled(bool enabled)
    {
        if (!CanConfigureRecoveryShortcut)
        {
            _log.Write(LogLevel.Warn, "未鉴权修改备用通道设置", "操作被拒绝");
            return false;
        }

        _config.SetRecoveryShortcutEnabled(enabled);
        _log.Write(
            enabled ? "启用" : "禁用",
            "PASSWORD_RECOVERY_SHORTCUT");
        return true;
    }

    public bool RemovePassword()
    {
        if (!HasPassword)
        {
            return false;
        }
        if (IsLocked)
        {
            _log.Write(LogLevel.Warn, "未鉴权删除密码", "操作被拒绝");
            return false;
        }

        _config.SetPasswordHash(null);
        if (HasPassword)
        {
            return false;
        }
        _config.SetRecoveryShortcutEnabled(false);
        _unlocked = true;
        _log.Write("密码已删除，配置保护已停用", "PASSWORD_REMOVED");
        return true;
    }

    /// <summary>Keeps the compatibility recovery gesture, when explicitly enabled.</summary>
    public bool RegisterCtrlL()
    {
        if (!HasPassword || !IsLocked || !RecoveryShortcutEnabled)
        {
            return false;
        }

        var now = DateTime.UtcNow;
        if (now - _lastCtrlL > TimeSpan.FromSeconds(2))
        {
            _ctrlLCount = 0;
        }
        _lastCtrlL = now;
        _ctrlLCount++;
        if (_ctrlLCount < 10)
        {
            return false;
        }
        _ctrlLCount = 0;
        _unlocked = true;
        _log.Write(LogLevel.Warn, "备用解锁通道已解锁配置", "PASSWORD_BACKDOOR");
        return true;
    }

    private void RemoveLegacyDefaultPassword()
    {
        var hash = _config.PasswordHash;
        if (string.IsNullOrWhiteSpace(hash)
            || !_encryption.VerifyPassword(LegacyBuiltInPassword, hash))
        {
            return;
        }

        _config.SetPasswordHash(null);
        _config.SetRecoveryShortcutEnabled(false);
        _log.Write("已移除旧版内置默认密码", "PASSWORD_DEFAULT_REMOVED");
    }
}
