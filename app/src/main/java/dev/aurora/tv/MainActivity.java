package dev.aurora.tv;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import java.text.SimpleDateFormat;
import java.util.*;

/** A small, remote-first home screen. Photos stay local; weather uses IP geolocation. */
public final class MainActivity extends Activity {
    private WallpaperView wallpaper;
    private LauncherRepository repository;
    private WeatherService weather;
    private LinearLayout appRow, inputRow;
    private TextView clock, date, weatherText, paletteText;
    private View firstApp;
    private AppsDrawer appsDrawer;
    private final java.util.List<LiquidGlassFrame> glassSurfaces = new ArrayList<>();
    private TvInputManager inputManager;
    private long lastGlassFrame;
    private static final int PICK_PHOTOS = 41;
    private boolean importingPhotos;
    private boolean liquidGlass;
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
        liquidGlass = getPreferences(MODE_PRIVATE).getBoolean("liquid_glass", true);
        repository = new LauncherRepository(this);
        weather = new WeatherService(this);
        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        wallpaper.setPhotos(PhotoStore.listFiles(this));
        // The landscape drifts very slowly: reuse blurred layers between samples.
        wallpaper.setOnFrameListener(() -> {
            long now = SystemClock.uptimeMillis();
            // A still photo has no future frame to repair a skipped final glass sample.
            if (!wallpaper.isPhotoStill()
                    && now - lastGlassFrame < (wallpaper.isTransitioning() ? 100 : 1000)) return;
            lastGlassFrame = now;
            for (LiquidGlassFrame glass : glassSurfaces) glass.refreshBackdrop();
            if(paletteText!=null)updateWallpaperLabel();
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
        TextView airplay = button("AirPlay", () -> showAirPlay());
        header.addView(airplay);
        LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) airplay.getLayoutParams();
        ap.rightMargin=dp(12);
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
        LiquidGlassFrame weatherCard = newGlass();
        weatherText = text("今日天气\n正在根据 IP 定位…", 18, Color.WHITE);
        weatherText.setPadding(dp(22), dp(15), dp(22), dp(15));
        weatherText.setLineSpacing(dp(5), 1);
        weatherCard.addView(weatherText, new FrameLayout.LayoutParams(-1,-1));
        focusable(weatherCard, () -> showWeatherSettings());
        hero.addView(weatherCard, new LinearLayout.LayoutParams(dp(244), dp(118)));
        page.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView appHeading = text("常用应用    ·    ↓ 所有应用", 16, 0xffe4edf1);
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
        // onResume populates once, including the initial launch.
    }

    private void populate() {
        if (glassSurfaces.size() > 1) {
            for (int i=1; i<glassSurfaces.size(); i++) glassSurfaces.get(i).dispose();
            glassSurfaces.subList(1, glassSurfaces.size()).clear();
        }
        appRow.removeAllViews(); inputRow.removeAllViews();
        java.util.List<LauncherRepository.AppEntry> entries = repository.apps();
        for (int i=0; i<Math.min(entries.size(), 6); i++) {
            LauncherRepository.AppEntry app = entries.get(i);
            LinearLayout wrap = column(); wrap.setGravity(Gravity.CENTER);
            FrameLayout card = app.isBilibili && !liquidGlass ? new FrameLayout(this) : newGlass();
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
            LiquidGlassFrame card=newGlass();
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
        updateWallpaperLabel();
        if(firstApp!=null) firstApp.requestFocus();
    }

    private void showApps() {
        if(appsDrawer!=null)return;
        View previous=getCurrentFocus();
        appsDrawer=new AppsDrawer(this,repository);
        appsDrawer.setOnDismissListener(dialog->{
            appsDrawer=null;
            if(previous!=null && previous.isAttachedToWindow())previous.requestFocus();
            else if(firstApp!=null)firstApp.requestFocus();
        });
        appsDrawer.show();
    }
    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Aurora TV").setItems(new String[]{"壁纸与照片轮播","天气与定位","液态玻璃","电视系统设置","系统输入源","AirPlay 接收","关于"},(d,n)->{
            if(n==0)showWallpaperSettings();
            if(n==1)showWeatherSettings();
            if(n==2)showGlassSettings();
            if(n==3)repository.openSettings();
            if(n==4)repository.openInputSettings();
            if(n==5)showAirPlay();
            if(n==6)new AlertDialog.Builder(this).setTitle("Aurora TV · 0.5.0").setMessage("简洁，回到观看本身。\n\n原创动态壁纸 · MIT License\n天气：Open-Meteo / CC BY 4.0\nIP 定位：ipwho.is · 城市级近似位置\n城市搜索：GeoNames\n本地照片仅在电视中保存，不上传。\n\n方向键移动 · 确认键打开\n菜单键打开设置").setPositiveButton("好",null).show();
        }).setNegativeButton("返回",null).show();
    }
    private void showAirPlay() {
        Intent receiver=getPackageManager().getLeanbackLaunchIntentForPackage(AirPlayCompanion.PACKAGE);
        if(receiver==null)receiver=getPackageManager().getLaunchIntentForPackage(AirPlayCompanion.PACKAGE);
        final Intent launch=receiver;
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("AirPlay 接收")
            .setMessage(launch==null ? "接收服务尚未安装，请按项目文档安装 AirPlay Server。" :
                "桌面启动时会自动开启接收服务，同一局域网的 iPhone、iPad 或 Mac 可通过屏幕镜像连接。\n\n打开接收端可修改名称和配对 PIN。服务后台常驻，返回桌面会确保服务启动。\n\n不支持受 DRM 保护的视频；多房间同步音频不作兼容保证。")
            .setNegativeButton("返回",null);
        if(launch!=null)dialog.setPositiveButton("打开接收端",(d,n)->{
            try { startActivity(launch); }
            catch(ActivityNotFoundException|SecurityException e){Toast.makeText(this,"无法打开接收端，请检查安装状态",Toast.LENGTH_LONG).show();}
        });
        dialog.show();
    }
    private void showGlassSettings() {
        new AlertDialog.Builder(this).setTitle("液态玻璃")
            .setMultiChoiceItems(new String[]{"启用液态玻璃"},new boolean[]{liquidGlass},(dialog,index,checked)->{
                liquidGlass=checked;
                getPreferences(MODE_PRIVATE).edit().putBoolean("liquid_glass",checked).apply();
                for(LiquidGlassFrame glass:glassSurfaces)glass.setLiquidEnabled(checked);
                populate();
            }).setPositiveButton("完成",null).show();
    }
    private LiquidGlassFrame newGlass() {
        LiquidGlassFrame glass=new LiquidGlassFrame(this,wallpaper);
        glass.setLiquidEnabled(liquidGlass);
        glassSurfaces.add(glass);
        return glass;
    }
    private void updateWallpaperLabel() {
        String label = wallpaper.isPhotoFallback() ? "照片暂不可用 · " + wallpaper.getPaletteName() : wallpaper.isUsingPhotos() ? "照片轮播 · " + wallpaper.getPhotoCount() + " 张" : wallpaper.getPaletteName()+"  /  动态壁纸";
        if(!label.contentEquals(paletteText.getText()))paletteText.setText(label);
    }
    private void showWallpaperSettings() {
        if(importingPhotos){Toast.makeText(this,"照片正在导入，请稍候",Toast.LENGTH_SHORT).show();return;}
        String[] effects = {"柔和淡化", "缓慢推拉", "横向滑移"};
        new AlertDialog.Builder(this).setTitle("壁纸与照片轮播").setItems(new String[]{
            "选择本地照片…", "背景来源 · " + (wallpaper.isUsingPhotos() ? "照片轮播" : "渐变壁纸"),
            "切换渐变配色 · " + wallpaper.getPaletteName(), "轮播间隔 · " + wallpaper.getIntervalSeconds() + " 秒",
            "过渡效果 · " + effects[wallpaper.getEffect()], "下一张照片", "清空已选照片"}, (d,n)->{
                if(n==0) {
                    if(importingPhotos){Toast.makeText(this,"照片正在导入，请稍候",Toast.LENGTH_SHORT).show();return;}
                    startActivityForResult(new Intent(this, PhotoPickerActivity.class), PICK_PHOTOS);
                }
                if(n==1) {
                    if(!wallpaper.isUsingPhotos() && wallpaper.getPhotoCount()==0){Toast.makeText(this,"请先选择本地照片",Toast.LENGTH_SHORT).show();return;}
                    wallpaper.usePhotos(!wallpaper.isUsingPhotos());updateWallpaperLabel();
                }
                if(n==2){wallpaper.usePhotos(false);wallpaper.cyclePalette();updateWallpaperLabel();}
                if(n==3) {
                    int[] seconds={15,30,60};String[] labels={"15 秒","30 秒","60 秒"};
                    int selected=wallpaper.getIntervalSeconds()==15?0:wallpaper.getIntervalSeconds()==30?1:2;
                    new AlertDialog.Builder(this).setTitle("轮播间隔").setSingleChoiceItems(labels,selected,(dialog,i)->{wallpaper.setIntervalSeconds(seconds[i]);dialog.dismiss();}).setNegativeButton("返回",null).show();
                }
                if(n==4)new AlertDialog.Builder(this).setTitle("过渡效果").setSingleChoiceItems(effects,wallpaper.getEffect(),(dialog,i)->{wallpaper.setEffect(i);dialog.dismiss();}).setNegativeButton("返回",null).show();
                if(n==5){wallpaper.nextPhoto();}
                if(n==6)new AlertDialog.Builder(this).setTitle("清空已选照片？").setMessage("仅清除桌面保存的照片副本，原始相册不受影响。")
                    .setPositiveButton("清空",(dialog,i)->{wallpaper.usePhotos(false);wallpaper.setPhotos(Collections.emptyList());PhotoStore.clear(this);updateWallpaperLabel();})
                    .setNegativeButton("取消",null).show();
            }).setNegativeButton("返回",null).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_PHOTOS || result!=RESULT_OK || data==null)return;
        LinkedHashSet<Uri> selected=new LinkedHashSet<>();
        if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)selected.add(data.getClipData().getItemAt(i).getUri());
        if(data.getData()!=null)selected.add(data.getData());
        if(selected.isEmpty())return;
        importingPhotos=true;
        Toast.makeText(this,"正在导入照片…",Toast.LENGTH_SHORT).show();
        PhotoStore.importSelection(this,new ArrayList<>(selected),(count,error)->{
            importingPhotos=false;
            if(isFinishing()||isDestroyed())return;
            if(error!=null){Toast.makeText(this,error,Toast.LENGTH_LONG).show();return;}
            wallpaper.setPhotos(PhotoStore.listFiles(this));wallpaper.usePhotos(true);lastGlassFrame=0;updateWallpaperLabel();
            Toast.makeText(this,"已选择 " + count + " 张照片",Toast.LENGTH_SHORT).show();
        });
    }
    private void showWeatherSettings() {
        new AlertDialog.Builder(this).setTitle("天气与定位").setItems(new String[]{
            "自动 IP 定位" + (weather.isAutomatic() ? " · 当前" : ""), "立即刷新天气与位置", "手动选择城市"},(d,n)->{
                if(n==0){weather.useAutomaticLocation();refreshWeather(true);}
                if(n==1)refreshWeather(true);
                if(n==2)chooseCity();
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
    private void refreshWeather() { refreshWeather(false); }
    private void refreshWeather(boolean force) {
        weatherText.setText(weather.isAutomatic() ? "正在根据 IP 定位…" : "正在更新天气…");
        weather.refresh(force,(result,error)->{
            if(isFinishing()||isDestroyed())return;
            if(result==null){weatherText.setText(error==null?"天气暂不可用\n点击重试或选择城市":error+"\n点击重试或选择城市");return;}
            String details = result.city+"   "+result.description()+"\n"+result.temperatureText()+"   "+result.rangeText()+"\n"+result.locationSource+" · "+result.updatedText();
            SpannableString formatted = new SpannableString(details);
            formatted.setSpan(new AbsoluteSizeSpan(11, true), details.lastIndexOf('\n')+1, details.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            weatherText.setText(formatted);
            weatherText.setTextSize(15);
        });
    }
    @Override protected void onResume(){super.onResume();AirPlayCompanion.ensureStarted(this);populate();for(LiquidGlassFrame glass:glassSurfaces)glass.setAnimationsEnabled(true);wallpaper.setRunning(true);handler.post(tick);handler.post(weatherTick);if(inputManager!=null)inputManager.registerCallback(inputCallback,handler);}
    @Override protected void onPause(){handler.removeCallbacks(tick);handler.removeCallbacks(weatherTick);wallpaper.setRunning(false);for(LiquidGlassFrame glass:glassSurfaces)glass.setAnimationsEnabled(false);if(inputManager!=null)inputManager.unregisterCallback(inputCallback);super.onPause();}
    @Override protected void onDestroy(){if(appsDrawer!=null)appsDrawer.dismissImmediately();for(LiquidGlassFrame glass:glassSurfaces)glass.dispose();weather.close();wallpaper.close();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);AirPlayCompanion.ensureStarted(this);if(appsDrawer!=null)appsDrawer.dismissImmediately();populate();}
    @Override public void onBackPressed(){if(firstApp!=null)firstApp.requestFocus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN && event.getAction()==KeyEvent.ACTION_DOWN){
            if(event.getRepeatCount()==0)showApps();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
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
            if(view instanceof LiquidGlassFrame)((LiquidGlassFrame)view).setGlassFocused(focused);
            else view.setForeground(focused?shape(0x08ffffff,14,0xddffffff):null);
        });
    }
}
