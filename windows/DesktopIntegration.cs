using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace DashboardWindows {
    public static class DesktopIntegration {
        public const string StartupValue="Dashboard2.0";
        public static string Executable { get { return Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"Dashboard.exe"); } }
        public static string StartupCommand(string executable) {
            if(String.IsNullOrWhiteSpace(executable) || executable.IndexOf('"')>=0) throw new ArgumentException("Invalid executable path.");
            return "\""+Path.GetFullPath(executable)+"\" --startup";
        }
        public static void SetStartup(bool enabled) {
            using(var key=Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")) {
                if(enabled) key.SetValue(StartupValue,StartupCommand(Executable),RegistryValueKind.String);
                else key.DeleteValue(StartupValue,false);
            }
        }
        public static bool IsStartupEnabled() {
            using(var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"))
                return key!=null && String.Equals(Convert.ToString(key.GetValue(StartupValue)),StartupCommand(Executable),StringComparison.OrdinalIgnoreCase);
        }
        [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr icon);
        public static Icon TrayIcon() {
            using(var bitmap=new Bitmap(32,32)) {
                using(var g=Graphics.FromImage(bitmap)) {
                    g.SmoothingMode=SmoothingMode.AntiAlias; g.Clear(Color.Transparent);
                    using(var fill=new SolidBrush(Color.FromArgb(36,42,56))) g.FillEllipse(fill,0,0,31,31);
                    using(var fill=new SolidBrush(Color.FromArgb(126,190,255))) {
                        g.FillRectangle(fill,8,8,6,6); g.FillRectangle(fill,18,8,6,6); g.FillRectangle(fill,8,18,6,6); g.FillRectangle(fill,18,18,6,6);
                    }
                }
                var handle=bitmap.GetHicon(); try { using(var borrowed=Icon.FromHandle(handle)) return (Icon)borrowed.Clone(); } finally { DestroyIcon(handle); }
            }
        }
    }
}
