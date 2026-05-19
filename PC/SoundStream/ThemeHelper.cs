using Microsoft.Win32;

public static class ThemeHelper
{
    public static bool IsDarkMode()
    {
        try
        {
            var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");

            var value = key?.GetValue("AppsUseLightTheme");
            return value is int v && v == 0;
        }
        catch
        {
            return false;
        }
    }
}