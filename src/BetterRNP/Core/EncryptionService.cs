using System.Security.Cryptography;
using System.Text;

namespace BetterRNP.Core;

public sealed class EncryptionService
{
    private const string DataSalt = "R@nd0mN@m3P!ck3r_S@lt_2026";
    private const int Pbkdf2Iterations = 100_000;
    private const int PasswordIterations = 210_000;
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private static readonly byte[] FixedSalt = Encoding.UTF8.GetBytes(DataSalt);

    public string EncryptData(string plainText, string schemeName)
    {
        var key = DeriveDataKey(schemeName);
        var plain = Encoding.UTF8.GetBytes(plainText);
        var nonce = RandomNumberGenerator.GetBytes(NonceSize);
        var cipher = new byte[plain.Length];
        var tag = new byte[TagSize];
        using (var aes = new AesGcm(key, TagSize))
        {
            aes.Encrypt(nonce, plain, cipher, tag);
        }

        var combined = new byte[nonce.Length + cipher.Length + tag.Length];
        Buffer.BlockCopy(nonce, 0, combined, 0, nonce.Length);
        Buffer.BlockCopy(cipher, 0, combined, nonce.Length, cipher.Length);
        Buffer.BlockCopy(tag, 0, combined, nonce.Length + cipher.Length, tag.Length);
        return Convert.ToBase64String(combined);
    }

    public string DecryptData(string encryptedBase64, string schemeName)
    {
        var combined = Convert.FromBase64String(encryptedBase64);
        if (combined.Length < NonceSize + TagSize)
        {
            throw new CryptographicException("加密数据长度无效");
        }

        var nonce = combined.AsSpan(0, NonceSize);
        var cipherLength = combined.Length - NonceSize - TagSize;
        var cipher = combined.AsSpan(NonceSize, cipherLength);
        var tag = combined.AsSpan(NonceSize + cipherLength, TagSize);
        var plain = new byte[cipherLength];

        using var aes = new AesGcm(DeriveDataKey(schemeName), TagSize);
        aes.Decrypt(nonce, cipher, tag, plain);
        return Encoding.UTF8.GetString(plain);
    }

    public string CalculateHash(string plainText)
        => Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(plainText)));

    public string HashPassword(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        var derived = Rfc2898DeriveBytes.Pbkdf2(
            password, salt, PasswordIterations, HashAlgorithmName.SHA256, 32);
        return $"pbkdf2-sha256${PasswordIterations}${Convert.ToBase64String(salt)}${Convert.ToBase64String(derived)}";
    }

    public bool VerifyPassword(string password, string stored)
    {
        try
        {
            if (stored.StartsWith("pbkdf2-sha256$", StringComparison.Ordinal))
            {
                var parts = stored.Split('$');
                if (parts.Length != 4 || !int.TryParse(parts[1], out var iterations))
                {
                    return false;
                }
                var salt = Convert.FromBase64String(parts[2]);
                var expected = Convert.FromBase64String(parts[3]);
                var actual = Rfc2898DeriveBytes.Pbkdf2(
                    password, salt, iterations, HashAlgorithmName.SHA256, expected.Length);
                return CryptographicOperations.FixedTimeEquals(expected, actual);
            }

            // Legacy Java format: unsalted single-round SHA-256, Base64 encoded.
            var expectedLegacy = Convert.FromBase64String(stored);
            var actualLegacy = SHA256.HashData(Encoding.UTF8.GetBytes(password));
            return CryptographicOperations.FixedTimeEquals(expectedLegacy, actualLegacy);
        }
        catch
        {
            return false;
        }
    }

    public bool NeedsPasswordUpgrade(string stored)
    {
        if (!stored.StartsWith("pbkdf2-sha256$", StringComparison.Ordinal))
        {
            return true;
        }
        var parts = stored.Split('$');
        return parts.Length != 4 || !int.TryParse(parts[1], out var iterations)
            || iterations < PasswordIterations;
    }

    private static byte[] DeriveDataKey(string schemeName)
    {
        var schemeSalt = $"R@nd0mN@m3P!ck3r_{schemeName}_2026";
        return Rfc2898DeriveBytes.Pbkdf2(
            schemeSalt, FixedSalt, Pbkdf2Iterations, HashAlgorithmName.SHA256, 16);
    }
}
