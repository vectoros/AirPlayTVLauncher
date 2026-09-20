package dev.aurora.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.media.tv.TvInputManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import java.text.SimpleDateFormat;
import java.util.*;

/** A small, remote-first home screen. All content stays local except opt-in weather. */
public final class MainActivity extends Activity {
    private WallpaperView wallpaper;
    private LauncherRepository repository;
    private WeatherService weather;
    private LinearLayout appRow, inputRow;
    private TextView clock, date, weatherText, paletteText;
    private View firstApp;
    private final java.util.List<View> glassSurfaces = new ArrayList<>();
    private TvInputManager inputManager;
    private long lastGlassFrame;
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String id, int state) { populate(); }
        @Override public void onInputAdded(String id) { populate(); }
        @Override public void onInputRemoved(String id) { populate(); }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(now));
            date.setText(new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now));
            handler.postDelayed(this, 15000);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        public void run() { refreshWeather(); handler.postDelayed(this, 30 * 60 * 1000L); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        repository = new LauncherRepository(this);
        weather = new WeatherService(this);
        if (weather.getLocation() == null) weather.setLocation(new WeatherService.Location("杭州", "杭州 · 浙江 · 中国", 30.2741, 120.1551));
        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        // The landscape drifts very slowly: reuse blurred layers between samples.
        wallpaper.setOnFrameListener(() -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastGlassFrame < 1000) return;
            lastGlassFrame = now;
            for (View glass : glassSurfaces) glass.invalidate();
        });
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(dp(44), dp(25), dp(44), dp(20));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = row();
        TextView brand = text("A U R O R A", 15, 0xffe0eef2);
        brand.setTypeface(null, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, -1, 1));
        TextView apps = button("所有应用", () -> showApps());
        header.addView(apps);
        TextView settings = button("设置", () -> showSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(70), dp(36)); sp.leftMargin=dp(12);
        header.addView(settings, sp);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(36)));

        LinearLayout hero = row(); hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout timeBlock = column();
        TextView overline = text("让此刻，慢下来", 16, 0xffd0e3e8);
        timeBlock.addView(overline);
        clock = text("", 66, Color.WHITE); clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeBlock.addView(clock, new LinearLayout.LayoutParams(-2, dp(83)));
        date = text("", 16, 0xffc9dbe2); timeBlock.addView(date);
        hero.addView(timeBlock, new LinearLayout.LayoutParams(0, -2, 1));
        GlassFrame weatherCard = new GlassFrame();
        weatherText = text("今日天气\n选择城市  ›", 18, Color.WHITE);
        weatherText.setPadding(dp(22), dp(15), dp(22), dp(15));
        weatherText.setLineSpacing(dp(5), 1);
        weatherCard.addView(weatherText, new FrameLayout.LayoutParams(-1,-1));
        focusable(weatherCard, () -> chooseCity());
        hero.addView(weatherCard, new LinearLayout.LayoutParams(dp(244), dp(118)));
        page.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView appHeading = text("常用应用", 16, 0xffe4edf1);
        page.addView(appHeading, new LinearLayout.LayoutParams(-1, dp(30)));
        HorizontalScrollView appScroll = new HorizontalScrollView(this);
        appScroll.setClipChildren(false); appScroll.setClipToPadding(false); appScroll.setHorizontalScrollBarEnabled(false);
        appRow = row(); appRow.setClipChildren(false); appRow.setClipToPadding(false);
        appRow.setPadding(dp(9), dp(10), dp(9), dp(4));
        appScroll.addView(appRow);
        page.addView(appScroll, new LinearLayout.LayoutParams(-1, dp(131)));

        LinearLayout inputHeading = row();
        inputHeading.addView(text("输入源", 15, 0xffe4edf1), new LinearLayout.LayoutParams(0, -1, 1));
        paletteText = text("", 11, 0xffa9c4ce);
        inputHeading.addView(paletteText);
        page.addView(inputHeading, new LinearLayout.LayoutParams(-1, dp(26)));
        inputRow = row(); inputRow.setClipChildren(false);
        page.addView(inputRow, new LinearLayout.LayoutParams(-1, dp(49)));
        setContentView(root);
        inputManager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        populate();
    }

    private void populate() {
        if (glassSurfaces.size() > 1) glassSurfaces.subList(1, glassSurfaces.size()).clear();
        appRow.removeAllViews(); inputRow.removeAllViews();
        java.util.List<LauncherRepository.AppEntry> entries = repository.apps();
        for (int i=0; i<Math.min(entries.size(), 6); i++) {
            LauncherRepository.AppEntry app = entries.get(i);
            LinearLayout wrap = column(); wrap.setGravity(Gravity.CENTER);
            FrameLayout card = app.isBilibili ? new FrameLayout(this) : new GlassFrame();
            card.setBackground(shape(app.isBilibili ? 0xffec71a3 : 0xb52d4056, 14, 0x33ffffff));
            card.setClipToOutline(true);
            if (app.icon != null) {
                ImageView icon = new ImageView(this); icon.setImageDrawable(app.icon); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER);
                card.addView(icon,ip);
            } else {
                TextView label=text(app.isBilibili ? "bilibili" : app.label.substring(0,1), 25, Color.WHITE);
                card.addView(label,new FrameLayout.LayoutParams(-1,-1));
            }
            card.setContentDescription(app.label);
            focusable(card, () -> repository.launch(app));
            wrap.addView(card,new LinearLayout.LayoutParams(dp(124),dp(80)));
            TextView label=text(app.isBilibili ? "哔哩哔哩 TV" : app.label, 12, 0xffe8eef6);
            label.setGravity(Gravity.CENTER); label.setSingleLine(true); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            wrap.addView(label,new LinearLayout.LayoutParams(dp(124),dp(29)));
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(124),-2); wp.rightMargin=dp(16);
            appRow.addView(wrap,wp);
            if(i==0) firstApp=card;
        }
        java.util.List<LauncherRepository.InputEntry> inputs=repository.hdmiInputs();
        for(LauncherRepository.InputEntry input: inputs) {
            GlassFrame card=new GlassFrame();
            TextView label=text("▱  "+input.label.replaceAll("\\s*\\(.*?\\)", "")+"   ·  "+(input.connected?"已连接":"未连接"),14,Color.WHITE);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(14),0,dp(10),0);
            card.addView(label,new FrameLayout.LayoutParams(-1,-1));
            card.setContentDescription(input.label);
            focusable(card,()->repository.launch(input));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1); lp.rightMargin=dp(12);
            inputRow.addView(card,lp);
        }
        if(inputs.isEmpty()) inputRow.addView(button("选择电视输入源",()->repository.openInputSettings()));
        paletteText.setText(wallpaper.getPaletteName()+"  /  动态壁纸");
        if(firstApp!=null) firstApp.requestFocus();
    }

    private void showApps() {
        java.util.List<LauncherRepository.AppEntry> entries=repository.apps();
        String[] labels=new String[entries.size()];
        for(int i=0;i<labels.length;i++) labels[i]=entries.get(i).label;
        new AlertDialog.Builder(this).setTitle("所有应用").setItems(labels,(d,n)->repository.launch(entries.get(n))).setNegativeButton("返回",null).show();
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Aurora TV").setItems(new String[]{"切换壁纸 · "+wallpaper.getPaletteName(),"天气城市","电视系统设置","系统输入源","关于"},(d,n)->{
            if(n==0){wallpaper.cyclePalette();paletteText.setText(wallpaper.getPaletteName()+"  /  动态壁纸");}
            if(n==1)chooseCity();
            if(n==2)repository.openSettings();
            if(n==3)repository.openInputSettings();
            if(n==4)new AlertDialog.Builder(this).setTitle("Aurora TV · 0.1.0").setMessage("简洁，回到观看本身。\n\n原创动态壁纸 · MIT License\n天气数据：Open-Meteo / CC BY 4.0\n城市数据：GeoNames\n\n方向键移动 · 确认键打开\n菜单键打开设置").setPositiveButton("好",null).show();
        }).setNegativeButton("返回",null).show();
    }
    private void chooseCity() {
        EditText query=new EditText(this);query.setSingleLine(true);query.setHint("输入城市，如 上海 / Shanghai");query.setTextColor(Color.WHITE);
        new AlertDialog.Builder(this).setTitle("天气城市").setMessage("仅查询所选城市天气，无需定位权限。数据来自 Open-Meteo。")
            .setView(query).setPositiveButton("搜索",(d,n)->{
                weatherText.setText("正在搜索城市…");
                weather.searchCity(query.getText().toString(),(locations,error)->{
                    if(isFinishing()||isDestroyed())return;
                    if(locations.isEmpty()){Toast.makeText(this,error==null?"没有找到城市":error,Toast.LENGTH_LONG).show();refreshWeather();return;}
                    String[] names=new String[locations.size()];for(int i=0;i<names.length;i++)names[i]=locations.get(i).label;
                    new AlertDialog.Builder(this).setTitle("选择城市").setItems(names,(dialog,index)->{weather.setLocation(locations.get(index));refreshWeather();}).setNegativeButton("返回",(dialog,index)->refreshWeather()).setOnCancelListener(dialog->refreshWeather()).show();
                });
            }).setNegativeButton("返回",null).show();
    }
    private void refreshWeather() {
        weather.refresh((result,error)->{
            if(isFinishing()||isDestroyed())return;
            if(result==null){weatherText.setText(error==null?"今日天气\n选择城市  ›":"天气暂不可用\n点击选择城市或重试");return;}
            String details = result.city+"   "+result.description()+"\n"+result.temperatureText()+"   "+result.rangeText()+"\n"+result.updatedText();
            SpannableString formatted = new SpannableString(details);
            formatted.setSpan(new AbsoluteSizeSpan(11, true), details.lastIndexOf('\n')+1, details.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            weatherText.setText(formatted);
            weatherText.setTextSize(15);
        });
    }
    @Override protected void onResume(){super.onResume();populate();wallpaper.setRunning(true);handler.post(tick);handler.post(weatherTick);if(inputManager!=null)inputManager.registerCallback(inputCallback,handler);}
    @Override protected void onPause(){handler.removeCallbacks(tick);handler.removeCallbacks(weatherTick);wallpaper.setRunning(false);if(inputManager!=null)inputManager.unregisterCallback(inputCallback);super.onPause();}
    @Override protected void onDestroy(){weather.close();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);populate();}
    @Override public void onBackPressed(){if(firstApp!=null)firstApp.requestFocus();}
    @Override public boolean onKeyUp(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_MENU){showSettings();return true;}return super.onKeyUp(key,event);}

    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setFontFeatureSettings("tnum");return t;}
    private TextView button(String label,Runnable action){TextView v=text(label,13,Color.WHITE);v.setGravity(Gravity.CENTER);v.setPadding(dp(15),0,dp(15),0);v.setBackground(shape(0x243b5069,18,0x35ffffff));v.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(36)));focusable(v,action);return v;}
    private GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private void focusable(View v,Runnable action){
        v.setFocusable(true);v.setClickable(true);v.setStateListAnimator(null);
        v.setOnClickListener(view->action.run());
        v.setOnFocusChangeListener((view,focused)->{
            view.animate().scaleX(focused?1.055f:1f).scaleY(focused?1.055f:1f).translationZ(focused?dp(10):0).setDuration(180).start();
            view.setForeground(focused?shape(0x08ffffff,14,0xddffffff):null);
        });
    }
    /** Blur only a matched wallpaper sample; text and icons remain crisp. */
    private final class GlassFrame extends FrameLayout {
        GlassFrame(){super(MainActivity.this);
            setBackground(shape(0x303a5369,16,0x45ffffff));setClipToOutline(true);
            View backdrop=new View(MainActivity.this){
                private final int[] origin=new int[2], scene=new int[2];
                @Override protected void onDraw(Canvas c){
                    getLocationOnScreen(origin);wallpaper.getLocationOnScreen(scene);
                    c.save();c.translate(scene[0]-origin[0],scene[1]-origin[1]);
                    wallpaper.drawScene(c,wallpaper.getWidth(),wallpaper.getHeight());c.restore();
                    c.drawColor(0x55425769);
                }
            };
            if(Build.VERSION.SDK_INT>=31)backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(22),dp(22),Shader.TileMode.CLAMP));
            addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
            glassSurfaces.add(backdrop);
        }
    }
}
