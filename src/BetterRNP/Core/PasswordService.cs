namespace BetterRNP.Core;

public sealed class PasswordService
{
    public const string BuiltInDefaultPassword = "#include<bits/stdc++.h>usingnamespacestd;intmain(){return0;}";

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
        EnsureDefaultPassword();
    }

    public bool IsLocked => !_unlocked;

    public void EnsureDefaultPassword()
    {
        if (!string.IsNullOrWhiteSpace(_config.PasswordHash))
        {
            return;
        }
        _config.SetPasswordHash(_encryption.HashPassword(BuiltInDefaultPassword));
        _log.Write("已初始化内置默认密码", "PASSWORD_INIT");
    }

    public bool VerifyAndUnlock(string password)
    {
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

    public bool ChangePassword(string newPassword)
    {
        if (newPassword.Length < 4 || newPassword.Length > 100)
        {
            _log.Write(LogLevel.Warn, $"长度={newPassword.Length}", "密码修改被拒绝");
            return false;
        }
        _config.SetPasswordHash(_encryption.HashPassword(newPassword));
        _log.Write("密码已修改", "PASSWORD_CHANGED");
        return true;
    }

    public bool IsUsingBuiltInDefaultPassword()
    {
        var hash = _config.PasswordHash;
        return string.IsNullOrWhiteSpace(hash)
            || _encryption.VerifyPassword(BuiltInDefaultPassword, hash);
    }

    public void Lock()
    {
        _unlocked = false;
        _log.Write("配置已锁定", "PASSWORD_LOCK");
    }

    public void Unlock() => _unlocked = true;

    /// <summary>Keeps the original Ctrl+L × 10 recovery shortcut.</summary>
    public bool RegisterCtrlL()
    {
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
        _log.Write(LogLevel.Warn, "Ctrl+L 备用通道已解锁配置", "PASSWORD_BACKDOOR");
        return true;
    }

    public static void ResetCtrlLCount()
    {
        // Kept as a compatibility-facing helper; counts are per PasswordWindow.
    }
}
