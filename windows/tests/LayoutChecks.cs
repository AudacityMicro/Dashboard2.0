using System;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using DashboardWindows;

static class LayoutChecks {
    // Exercise the actual WPF layout and drag handlers without opening a desktop window,
    // connecting to Google, changing startup settings, or installing a tray icon.
    public static void Run(string root,Action<bool,string> check) {
        Exception failure=null;
        var thread=new Thread(()=> {
            try {
                var store=new Store(root);
                var board=new Board(); for(int i=0;i<100;i++) board.tasks.Add(new Todo { id="task-"+i,title="Task "+i }); store.Accept(Json.Write(board));
                var widget=new Widget(store,false);
                try {
                    var grid=(Grid)widget.Content;
                    var scroll=grid.Children.OfType<ScrollViewer>().Single();
                    check(!widget.ShowInTaskbar,"widget stays out of the taskbar");
                    check(scroll.VerticalScrollBarVisibility==ScrollBarVisibility.Hidden,"main scrollbar is hidden without disabling scrolling");
                    grid.Measure(new Size(widget.Width,widget.Height)); grid.Arrange(new Rect(0,0,widget.Width,widget.Height)); grid.UpdateLayout();
                    scroll.ScrollToVerticalOffset(100);
                    var frame=new System.Windows.Threading.DispatcherFrame();
                    System.Windows.Threading.Dispatcher.CurrentDispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ApplicationIdle,new Action(()=>frame.Continue=false));
                    System.Windows.Threading.Dispatcher.PushFrame(frame); scroll.UpdateLayout();
                    check(scroll.ExtentHeight>scroll.ViewportHeight && scroll.VerticalOffset>0,"long task lists remain scrollable without a visible scrollbar");
                    var handles=grid.Children.OfType<Grid>().Single(g=>Grid.GetRow(g)==3).Children.OfType<Thumb>().ToArray();
                    double height=widget.Height;
                    handles[0].RaiseEvent(new DragDeltaEventArgs(0,100) { RoutedEvent=Thumb.DragDeltaEvent });
                    check(widget.Height==height+100,"dragging the bottom handle enlarges the window");
                    handles[0].RaiseEvent(new DragDeltaEventArgs(0,-50) { RoutedEvent=Thumb.DragDeltaEvent });
                    check(widget.Height==height+50,"dragging the bottom handle upward makes the window smaller");
                    handles[0].RaiseEvent(new DragCompletedEventArgs(0,-50,false) { RoutedEvent=Thumb.DragCompletedEvent });
                    check(new Store(root).Snapshot().config.height==widget.Height,"drag completion persists the new window size");
                    var header=grid.Children.OfType<DockPanel>().Single();
                    check(!header.Children.OfType<TextBlock>().Any(),"header has a drag handle rather than a Dashboard label");
                    var move=header.Children.OfType<Thumb>().Single(); double left=widget.Left, width=widget.Width; height=widget.Height;
                    widget.PinInPlace(true);
                    move.RaiseEvent(new DragDeltaEventArgs(20,20) { RoutedEvent=Thumb.DragDeltaEvent });
                    handles[0].RaiseEvent(new DragDeltaEventArgs(0,100) { RoutedEvent=Thumb.DragDeltaEvent });
                    handles[1].RaiseEvent(new DragDeltaEventArgs(100,100) { RoutedEvent=Thumb.DragDeltaEvent });
                    check(widget.Left==left && widget.Width==width && widget.Height==height && widget.ResizeMode==ResizeMode.NoResize,"pinning blocks movement and both resize handles");
                    check(new Store(root).Snapshot().config.pinned,"pin state survives a restart");
                    var message=typeof(Widget).GetMethod("WindowMessage",System.Reflection.BindingFlags.NonPublic|System.Reflection.BindingFlags.Instance);
                    var moveMessage=new object[] { IntPtr.Zero,0x0112,new IntPtr(0xF010),IntPtr.Zero,false };
                    var sizeMessage=new object[] { IntPtr.Zero,0x0112,new IntPtr(0xF000),IntPtr.Zero,false };
                    message.Invoke(widget,moveMessage); message.Invoke(widget,sizeMessage);
                    check((bool)moveMessage[4] && (bool)sizeMessage[4],"pinning also blocks native Windows move and resize commands");
                    widget.PinInPlace(false); move.RaiseEvent(new DragDeltaEventArgs(15,0) { RoutedEvent=Thumb.DragDeltaEvent });
                    moveMessage[4]=false; message.Invoke(widget,moveMessage);
                    check(!(bool)moveMessage[4],"unpinning restores native Windows movement");
                    check(widget.Left==left+15 && handles.All(h=>h.IsEnabled),"unpinning restores window movement and resizing");
                } finally { widget.Close(); }
            } catch(Exception e) { failure=e; }
        });
        thread.SetApartmentState(ApartmentState.STA); thread.Start();
        if(!thread.Join(15000)) throw new Exception("Layout checks timed out.");
        if(failure!=null) throw failure;
    }
}
