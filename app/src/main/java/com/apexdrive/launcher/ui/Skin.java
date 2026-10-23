package com.apexdrive.launcher.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.apexdrive.launcher.R;
import com.apexdrive.launcher.utils.Look;
import com.apexdrive.launcher.utils.Look.Token;

import java.lang.reflect.Constructor;
import java.util.HashMap;

/**
 * Paints views in the current {@link Look} without restarting the activity.
 *
 * As a LayoutInflater factory it notes which colour and background resource each view was
 * inflated with (one tag each), and paints the view at once when a non-default look is active.
 * {@link #apply(View)} repaints a built tree after the look changes. With the default look
 * nothing is repainted, so the resources draw exactly as before.
 *
 * Drawables are rebuilt in code to match res/drawable/ad_bg_*.xml, using the look's colours
 * and corner radius. Translucent cards are plain alpha colours (no blur) for the Mali-400.
 */
public final class Skin implements LayoutInflater.Factory2 {

    /** Colour resources in Token order. */
    private static final int[] COLOR_RES = {
            R.color.ad_canvas, R.color.ad_surface_1, R.color.ad_surface_2, R.color.ad_surface_3,
            R.color.ad_fill_pressed, R.color.ad_segment_selected,
            R.color.ad_label_1, R.color.ad_label_2, R.color.ad_label_3, R.color.ad_label_4,
            R.color.ad_separator, R.color.ad_outline,
            R.color.ad_accent, R.color.ad_accent_pressed, R.color.ad_on_accent, R.color.ad_accent_text,
            R.color.ad_ok, R.color.ad_caution, R.color.ad_alert, R.color.ad_on_ok, R.color.ad_on_caution,
            R.color.ad_on_alert, R.color.ad_glass, R.color.ad_glass_solid, R.color.ad_glass_edge,
            R.color.ad_glass_hi, R.color.ad_scrim, R.color.ad_dim, R.color.ad_clear_glass
    };
    private static final SparseIntArray TOKEN_OF = new SparseIntArray(COLOR_RES.length);

    static {
        for (int i = 0; i < COLOR_RES.length; i++) TOKEN_OF.put(COLOR_RES[i], i);
    }

    private static final String[] PREFIXES = {"android.widget.", "android.view.", "android.webkit."};
    private static final HashMap<String, Constructor<? extends View>> CONSTRUCTORS = new HashMap<>();
    private static final Class<?>[] SIGNATURE = {Context.class, AttributeSet.class};

    private final Context context;
    private final AppCompatDelegate delegate;
    private final float density;
    private Look look;
    private boolean active;

    public Skin(Context context, AppCompatDelegate delegate, Look look) {
        this.context = context;
        this.delegate = delegate;
        this.density = context.getResources().getDisplayMetrics().density;
        setLook(look);
    }

    public void setLook(Look look) {
        this.look = look;
        this.active = !look.isResourceLook();
    }

    public Look look() {
        return look;
    }

    /** False while the look equals the resources and views are left as inflated. */
    public boolean isActive() {
        return active;
    }

    /** A design-system colour resource in the current look. */
    public int color(int colorRes) {
        int t = TOKEN_OF.get(colorRes, -1);
        return t >= 0 ? look.color(Token.values()[t]) : ContextCompat.getColor(context, colorRes);
    }

    public int color(Token t) {
        return look.color(t);
    }

    // --- Inflation ------------------------------------------------------------------------

    @Override
    public View onCreateView(View parent, String name, Context ctx, AttributeSet attrs) {
        View v = delegate.createView(parent, name, ctx, attrs);
        if (v == null) v = createPlain(name, ctx, attrs);
        if (v != null) {
            note(v, ctx, attrs);
            if (active) paint(v);
        }
        return v;
    }

    @Override
    public View onCreateView(String name, Context ctx, AttributeSet attrs) {
        return onCreateView(null, name, ctx, attrs);
    }

    private static View createPlain(String name, Context ctx, AttributeSet attrs) {
        try {
            Constructor<? extends View> c = CONSTRUCTORS.get(name);
            if (c == null) {
                Class<? extends View> cls = null;
                if (name.indexOf('.') >= 0) {
                    cls = Class.forName(name, false, ctx.getClassLoader()).asSubclass(View.class);
                } else {
                    for (String prefix : PREFIXES) {
                        try {
                            cls = Class.forName(prefix + name, false, ctx.getClassLoader()).asSubclass(View.class);
                            break;
                        } catch (ClassNotFoundException ignored) {
                        }
                    }
                }
                if (cls == null) return null;
                c = cls.getConstructor(SIGNATURE);
                c.setAccessible(true);
                CONSTRUCTORS.put(name, c);
            }
            return c.newInstance(ctx, attrs);
        } catch (Exception e) {
            // Let LayoutInflater create it the normal way; it just won't follow the look.
            return null;
        }
    }

    private static void note(View v, Context ctx, AttributeSet attrs) {
        TypedArray a = ctx.obtainStyledAttributes(attrs, R.styleable.AdSkin);
        try {
            tagIfSet(v, R.id.skin_bg, a.getResourceId(R.styleable.AdSkin_android_background, 0));
            if (v instanceof TextView) {
                tagIfSet(v, R.id.skin_text, a.getResourceId(R.styleable.AdSkin_android_textColor, 0));
                tagIfSet(v, R.id.skin_dtint, a.getResourceId(R.styleable.AdSkin_drawableTint, 0));
            } else if (v instanceof ImageView) {
                int tint = a.getResourceId(R.styleable.AdSkin_tint, 0);
                if (tint == 0) tint = a.getResourceId(R.styleable.AdSkin_android_tint, 0);
                tagIfSet(v, R.id.skin_tint, tint);
            }
        } finally {
            a.recycle();
        }
    }

    private static void tagIfSet(View v, int key, int res) {
        if (res != 0) v.setTag(key, res);
    }

    // --- Painting -------------------------------------------------------------------------

    /** Repaints a built view tree in the current look (also back to the default look). */
    public void apply(View root) {
        paint(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    /** Sets a text colour from a colour resource and remembers it, so a later repaint keeps it. */
    public void setTextColor(TextView tv, int colorRes) {
        if (tv == null) return;
        tv.setTag(R.id.skin_text, colorRes);
        tv.setTextColor(color(colorRes));
    }

    private void paint(View v) {
        Object bg = v.getTag(R.id.skin_bg);
        if (bg != null) {
            Drawable d = background((Integer) bg);
            if (d != null) {
                v.setBackground(d);
                fitElevation(v, (Integer) bg);
            }
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Object text = tv.getTag(R.id.skin_text);
            if (text != null && isColor((Integer) text)) tv.setTextColor(color((Integer) text));
            Object dtint = tv.getTag(R.id.skin_dtint);
            if (dtint != null && isColor((Integer) dtint)) {
                TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(color((Integer) dtint)));
            }
            Object base = tv.getTag(R.id.skin_text_px);
            if (base == null) {
                base = tv.getTextSize();
                tv.setTag(R.id.skin_text_px, base);
            }
            float px = (Float) base * look.textScale();
            if (Math.abs(px - tv.getTextSize()) > 0.1f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            if (v instanceof SwitchCompat) paintSwitch((SwitchCompat) v);
        } else if (v instanceof ImageView) {
            Object tint = v.getTag(R.id.skin_tint);
            if (tint != null && isColor((Integer) tint)) {
                ImageViewCompat.setImageTintList((ImageView) v, ColorStateList.valueOf(color((Integer) tint)));
            }
        }
    }

    private boolean isColor(int res) {
        return TOKEN_OF.get(res, -1) >= 0;
    }

    /** Shadows show through see-through cards as grey smudges, so drop them while cards are translucent. */
    private void fitElevation(View v, int bgRes) {
        if (!isCard(bgRes)) return;
        Object saved = v.getTag(R.id.skin_elevation);
        if (saved == null) {
            saved = v.getElevation();
            v.setTag(R.id.skin_elevation, saved);
        }
        boolean flat = look.cardAlpha() < 1f || look.outlinedCards();
        v.setElevation(flat ? 0f : (Float) saved);
    }

    private static boolean isCard(int res) {
        return res == R.drawable.ad_bg_tile || res == R.drawable.ad_bg_tile_pressable
                || res == R.drawable.ad_bg_glass_panel || res == R.drawable.ad_bg_glass_capsule;
    }

    // --- Drawables (mirror res/drawable/ad_bg_*.xml) --------------------------------------

    private static final int[] PRESSED = {android.R.attr.state_pressed};
    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int[] CHECKED = {android.R.attr.state_checked};
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] ANY = {};
    private static final float PILL = -1f;
    private static final float OVAL = -2f;

    /** Builds the look's version of a background resource, or null to leave it as inflated. */
    public Drawable background(int res) {
        if (isColor(res)) return new ColorDrawable(color(res));
        Resources r = context.getResources();
        float tile = radius(r, R.dimen.ad_radius_tile);
        float inner = radius(r, R.dimen.ad_radius_inner);
        float control = radius(r, R.dimen.ad_radius_control);
        int s1 = color(Token.SURFACE_1), s2 = color(Token.SURFACE_2), s3 = color(Token.SURFACE_3);
        int pressed = color(Token.FILL_PRESSED), accent = color(Token.ACCENT), clear = 0;

        if (res == R.drawable.ad_bg_tile) return card(tile, s1);
        if (res == R.drawable.ad_bg_tile_pressable) {
            return states(PRESSED, shape(tile, pressed), FOCUSED, ring(tile, s1), ANY, card(tile, s1));
        }
        if (res == R.drawable.ad_bg_row) {
            return states(PRESSED, shape(inner, pressed), FOCUSED, ring(inner, s2), ANY, shape(inner, s2));
        }
        if (res == R.drawable.ad_bg_row_raised) {
            return states(PRESSED, shape(inner, pressed), FOCUSED, ring(inner, s1), ANY, shape(inner, s1));
        }
        if (res == R.drawable.ad_bg_btn_primary) {
            return states(PRESSED, shape(PILL, color(Token.ACCENT_PRESSED)), ANY, shape(PILL, accent));
        }
        if (res == R.drawable.ad_bg_btn_secondary) {
            return states(PRESSED, shape(PILL, pressed), FOCUSED, ring(PILL, s3), ANY, shape(PILL, s3));
        }
        if (res == R.drawable.ad_bg_btn_quiet) {
            return states(PRESSED, shape(PILL, pressed), FOCUSED, ring(PILL, clear), ANY, shape(PILL, clear));
        }
        if (res == R.drawable.ad_bg_btn_close) return states(PRESSED, shape(OVAL, pressed), ANY, shape(OVAL, s3));
        if (res == R.drawable.ad_bg_field) {
            GradientDrawable normal = shape(PILL, s1);
            normal.setStroke(dp(2), color(Token.OUTLINE));
            GradientDrawable focused = shape(PILL, s1);
            focused.setStroke(dp(3), accent);
            return states(DISABLED, shape(PILL, s2), FOCUSED, focused, ANY, normal);
        }
        if (res == R.drawable.ad_bg_glass_capsule) return glass(PILL);
        if (res == R.drawable.ad_bg_glass_panel) return glass(tile);
        if (res == R.drawable.ad_bg_glass_sheet) return glass(radius(r, R.dimen.ad_radius_sheet));
        if (res == R.drawable.ad_bg_notification) return glass(dp(40));
        if (res == R.drawable.ad_bg_list_item) {
            return states(PRESSED, shape(control, pressed), SELECTED, shape(control, color(Token.SEGMENT_SELECTED)),
                    ANY, shape(control, clear));
        }
        if (res == R.drawable.ad_bg_rail_item) {
            float rail = dp(20) * look.radiusScale();
            return states(PRESSED, shape(rail, pressed), SELECTED, shape(rail, accent), FOCUSED, ring(rail, clear),
                    ANY, shape(rail, clear));
        }
        if (res == R.drawable.ad_bg_segment) return shape(PILL, s3);
        if (res == R.drawable.ad_bg_segment_item) {
            return states(SELECTED, shape(PILL, color(Token.SEGMENT_SELECTED)), ANY, shape(PILL, clear));
        }
        if (res == R.drawable.ad_bg_art) return shape(control, s2);
        if (res == R.drawable.ad_bg_avatar) return shape(OVAL, s2);
        if (res == R.drawable.ad_bg_badge_ok) return shape(OVAL, color(Token.OK));
        if (res == R.drawable.ad_bg_badge_caution) return shape(OVAL, color(Token.CAUTION));
        if (res == R.drawable.ad_bg_badge_alert) return shape(OVAL, color(Token.ALERT));
        if (res == R.drawable.ad_bg_badge_neutral) return shape(OVAL, s3);
        if (res == R.drawable.ad_bg_scrim) return new ColorDrawable(color(Token.SCRIM));
        return null;
    }

    private void paintSwitch(SwitchCompat sw) {
        GradientDrawable thumbOn = shape(OVAL, color(Token.ON_ACCENT));
        thumbOn.setSize(dp(28), dp(28));
        GradientDrawable thumbOff = shape(OVAL, color(Token.OUTLINE));
        thumbOff.setSize(dp(28), dp(28));
        int inset = dp(4);
        sw.setThumbDrawable(states(CHECKED, new InsetDrawable(thumbOn, inset), ANY, new InsetDrawable(thumbOff, inset)));
        GradientDrawable on = shape(dp(18), color(Token.ACCENT));
        on.setSize(dp(60), dp(36));
        GradientDrawable off = shape(dp(18), color(Token.SURFACE_3));
        off.setSize(dp(60), dp(36));
        off.setStroke(dp(2), color(Token.OUTLINE));
        sw.setTrackDrawable(states(CHECKED, on, ANY, off));
        // New drawables start in the default state; give them the switch's checked state now.
        sw.refreshDrawableState();
    }

    private float radius(Resources r, int dimen) {
        return r.getDimension(dimen) * look.radiusScale();
    }

    private GradientDrawable shape(float radius, int color) {
        GradientDrawable g = new GradientDrawable();
        if (radius == OVAL) {
            g.setShape(GradientDrawable.OVAL);
        } else {
            g.setShape(GradientDrawable.RECTANGLE);
            g.setCornerRadius(radius == PILL ? dp(999) : radius);
        }
        g.setColor(color);
        return g;
    }

    /** Focus state: the fill with a 3 dp accent ring. */
    private GradientDrawable ring(float radius, int fill) {
        GradientDrawable g = shape(radius, fill);
        g.setStroke(dp(3), color(Token.ACCENT));
        return g;
    }

    private GradientDrawable card(float radius, int fill) {
        GradientDrawable g = shape(radius, fill);
        if (look.outlinedCards()) g.setStroke(dp(2), color(Token.SEPARATOR));
        return g;
    }

    private GradientDrawable glass(float radius) {
        GradientDrawable g = shape(radius, color(Token.GLASS_SOLID));
        g.setStroke(dp(look.outlinedCards() ? 2 : 1), color(Token.GLASS_EDGE));
        return g;
    }

    private static StateListDrawable states(Object... stateDrawablePairs) {
        StateListDrawable s = new StateListDrawable();
        for (int i = 0; i < stateDrawablePairs.length; i += 2) {
            s.addState((int[]) stateDrawablePairs[i], (Drawable) stateDrawablePairs[i + 1]);
        }
        return s;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // --- Wallpaper ------------------------------------------------------------------------

    /**
     * The drawable behind every screen: the canvas colour, a built-in gradient, or the
     * user's image (already scaled to the screen) under a light scrim so cards stay legible.
     */
    public Drawable wallpaper(int wallpaper, Bitmap image) {
        int canvas = look.color(Token.CANVAS);
        if (wallpaper == Look.WALLPAPER_CUSTOM) {
            if (image == null) return new ColorDrawable(canvas);
            BitmapDrawable bmp = new BitmapDrawable(context.getResources(), image);
            bmp.setFilterBitmap(true);
            return new LayerDrawable(new Drawable[]{bmp,
                    new ColorDrawable(look.night ? 0x66000000 : 0x47FFFFFF)});
        }
        int[] stops = Look.wallpaperStops(wallpaper, look.night);
        if (stops == null) return new ColorDrawable(canvas);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, stops);
        base.setDither(true);
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setColors(new int[]{Look.wallpaperGlow(wallpaper, look.night), 0});
        glow.setGradientCenter(0.85f, 0.05f);
        glow.setGradientRadius(dp(560));
        glow.setDither(true);
        return new LayerDrawable(new Drawable[]{base, glow});
    }
}
