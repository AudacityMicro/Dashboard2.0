using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace DashboardWindows {
    public enum CaptureIcon { Note, Microphone, Camera, Upload }
    public static class WidgetIcons {
        public static UIElement Create(CaptureIcon icon) {
            string path;
            switch(icon) {
                case CaptureIcon.Note: path="M14 3 H5 A2 2 0 0 0 3 5 V19 A2 2 0 0 0 5 21 H19 A2 2 0 0 0 21 19 V10 M10 14 L10.7 10.7 L18.8 2.6 A1.8 1.8 0 0 1 21.4 5.2 L13.3 13.3 Z M17 4.5 L19.5 7"; break;
                case CaptureIcon.Microphone: path="M9 5 A3 3 0 0 1 15 5 V11 A3 3 0 0 1 9 11 Z M5 10 V11 A7 7 0 0 0 19 11 V10 M12 18 V22 M8 22 H16"; break;
                case CaptureIcon.Camera: path="M8 5 L9.5 2.5 H14.5 L16 5 H20 A2 2 0 0 1 22 7 V19 A2 2 0 0 1 20 21 H4 A2 2 0 0 1 2 19 V7 A2 2 0 0 1 4 5 Z M16 13 A4 4 0 1 1 8 13 A4 4 0 1 1 16 13"; break;
                default: path="M12 16 V3 M7 8 L12 3 L17 8 M3 15 V20 A1 1 0 0 0 4 21 H20 A1 1 0 0 0 21 20 V15"; break;
            }
            var canvas=new Canvas { Width=24,Height=24 };
            canvas.Children.Add(new System.Windows.Shapes.Path { Data=Geometry.Parse(path),Stroke=Brushes.White,StrokeThickness=1.7,StrokeStartLineCap=PenLineCap.Round,StrokeEndLineCap=PenLineCap.Round,StrokeLineJoin=PenLineJoin.Round });
            return new Viewbox { Width=20,Height=20,Child=canvas };
        }
    }
}
