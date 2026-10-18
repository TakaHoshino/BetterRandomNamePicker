using System.Windows;
using System.Windows.Media;

namespace BetterRNP.TimerPlugin.Views;

/// <summary>所有按钮图标都由矢量几何绘制，不依赖字体或位图资源。</summary>
internal static class Icons
{
    public static Geometry Play { get; } = Frozen("M 0,0 L 12,7 L 0,14 Z");

    public static Geometry Pause { get; } = Frozen("M 0,0 H 4.4 V 14 H 0 Z M 8.6,0 H 13 V 14 H 8.6 Z");

    public static Geometry Close { get; } = Frozen("M 0,0 L 9,9 M 9,0 L 0,9");

    public static Geometry ChevronUp { get; } = Frozen("M 0,4.5 L 4,0.5 L 8,4.5");

    public static Geometry ChevronDown { get; } = Frozen("M 0,0.5 L 4,4.5 L 8,0.5");

    /// <summary>重置图标：一段 270° 圆弧，顶部缺口处接一个向右的箭头。</summary>
    public static Geometry Reset { get; } = BuildReset();

    private static Geometry BuildReset()
    {
        const double cx = 7.6;
        const double cy = 7.6;
        const double radius = 5.9;

        var start = PointAt(cx, cy, radius, 0);
        var end = PointAt(cx, cy, radius, 270);

        var arc = new PathFigure
        {
            StartPoint = start,
            IsClosed = false,
            IsFilled = false
        };
        arc.Segments.Add(new ArcSegment
        {
            Point = end,
            Size = new Size(radius, radius),
            IsLargeArc = true,
            SweepDirection = SweepDirection.Clockwise
        });

        // 圆弧在顶部结束，顺时针方向的切线朝右；箭头指向缺口。
        var head = new PathFigure
        {
            StartPoint = new Point(end.X + 3.2, end.Y),
            IsClosed = true,
            IsFilled = true
        };
        head.Segments.Add(new LineSegment(new Point(end.X, end.Y - 2.1), true));
        head.Segments.Add(new LineSegment(new Point(end.X, end.Y + 2.1), true));

        var geometry = new PathGeometry();
        geometry.Figures.Add(arc);
        geometry.Figures.Add(head);
        geometry.Freeze();
        return geometry;
    }

    private static Point PointAt(double cx, double cy, double radius, double degrees)
        => new(cx + radius * Math.Cos(Radians(degrees)), cy + radius * Math.Sin(Radians(degrees)));

    private static double Radians(double degrees) => degrees * Math.PI / 180.0;

    private static Geometry Frozen(string pathData)
    {
        var geometry = Geometry.Parse(pathData);
        geometry.Freeze();
        return geometry;
    }
}
