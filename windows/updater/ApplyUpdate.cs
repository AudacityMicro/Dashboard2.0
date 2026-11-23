using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;
using DashboardWindows;

class ApplyUpdate {
    [STAThread] static void Main(string[] args) {
        try {
            int pid; if(args.Length!=2 || !Int32.TryParse(args[0],out pid)) throw new InvalidDataException("Invalid update request.");
            string stage=AppDomain.CurrentDomain.BaseDirectory;
            string updateRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Dashboard2.0","Windows","updates")+Path.DirectorySeparatorChar;
            if(!Path.GetFullPath(stage).StartsWith(updateRoot,StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Update must run from the private staging folder.");
            var destination=Path.GetFullPath(args[1]); var exe=Path.Combine(destination,"Dashboard.exe");
            UpdatePackage.Validate(stage,null);
            Process parent=null; try { parent=Process.GetProcessById(pid); } catch(ArgumentException) {}
            if(parent!=null) using(parent) { if(!parent.WaitForExit(60000)) throw new IOException("Dashboard is still open. Close or save any draft, then try updating again. No installed files were changed."); }
            UpdatePackage.Apply(stage,destination);
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute=true,WorkingDirectory=destination });
        } catch(Exception e) { MessageBox.Show("Update could not finish: "+e.Message+"\nYour captures and Google connection remain in your Windows profile.","Dashboard update",MessageBoxButtons.OK,MessageBoxIcon.Warning); }
    }
}
