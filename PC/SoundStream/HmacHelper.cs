using System.Security.Cryptography;
using System.Text;

public static class HmacHelper
{
    private static readonly string secret = "SoundStreamSuperKey123";

    public static string Sign(string message)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        var hash = hmac.ComputeHash(Encoding.UTF8.GetBytes(message));
        return Convert.ToBase64String(hash);
    }

    public static bool Verify(string message, string signature)
    {
        var expected = Sign(message);
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(expected),
            Encoding.UTF8.GetBytes(signature)
        );
    }
}