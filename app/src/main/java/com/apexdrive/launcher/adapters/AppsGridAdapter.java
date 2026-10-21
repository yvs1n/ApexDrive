package com.apexdrive.launcher.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.apexdrive.launcher.R;
import com.apexdrive.launcher.models.AppInfo;

import java.util.ArrayList;
import java.util.List;

public class AppsGridAdapter extends BaseAdapter {
    public interface OnAppClickListener {
        void onAppClick(AppInfo app);
    }

    private final List<AppInfo> fullList;
    private final List<AppInfo> filteredList;
    private final LayoutInflater inflater;
    private OnAppClickListener clickListener;
    private String currentQuery = "";
    private final List<String> favouritePackages = new ArrayList<>();
    private boolean driving;
    private int drivingTileHeight;

    /** While driving only the favourites are shown, as larger tiles. */
    public static final int DRIVING_LIMIT = 6;

    public AppsGridAdapter(Context context, List<AppInfo> apps) {
        this.fullList = new ArrayList<>(apps);
        this.filteredList = new ArrayList<>(apps);
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.clickListener = listener;
    }

    public void setFavouritePackages(List<String> packages) {
        favouritePackages.clear();
        for (String p : packages) if (p != null && !p.isEmpty()) favouritePackages.add(p);
        filter(currentQuery);
    }

    public void setDriving(boolean driving, int tileHeightPx) {
        this.driving = driving;
        this.drivingTileHeight = tileHeightPx;
        filter(currentQuery);
    }

    public void updateData(List<AppInfo> apps) {
        this.fullList.clear();
        this.fullList.addAll(apps);
        filter(currentQuery);
    }

    @Override
    public int getCount() {
        return filteredList.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return filteredList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_app_tile, parent, false);
            holder = new ViewHolder();
            holder.ivIcon = convertView.findViewById(R.id.ivAppIcon);
            holder.tvName = convertView.findViewById(R.id.tvAppName);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ViewGroup.LayoutParams lp = convertView.getLayoutParams();
        int height = driving && drivingTileHeight > 0 ? drivingTileHeight
                : convertView.getResources().getDimensionPixelSize(R.dimen.ad_app_tile_height);
        if (lp != null && lp.height != height) {
            lp.height = height;
            convertView.setLayoutParams(lp);
        }
        holder.tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, convertView.getResources()
                .getDimension(driving ? R.dimen.ad_text_glance : R.dimen.ad_text_label));

        final AppInfo app = getItem(position);
        holder.tvName.setText(app.getLabel());
        holder.ivIcon.setImageDrawable(app.getIcon());

        convertView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onAppClick(app);
            }
        });

        return convertView;
    }

    public void filter(String query) {
        this.currentQuery = (query != null) ? query : "";
        filteredList.clear();
        if (driving) {
            for (String pkg : favouritePackages) {
                for (AppInfo app : fullList) {
                    if (pkg.equals(app.getPackageName()) && !filteredList.contains(app)) {
                        filteredList.add(app);
                        break;
                    }
                }
            }
            for (AppInfo app : fullList) {
                if (filteredList.size() >= DRIVING_LIMIT) break;
                if (!filteredList.contains(app)) filteredList.add(app);
            }
            while (filteredList.size() > DRIVING_LIMIT) filteredList.remove(filteredList.size() - 1);
        } else if (currentQuery.trim().isEmpty()) {
            filteredList.addAll(fullList);
        } else {
            String lower = currentQuery.toLowerCase().trim();
            for (AppInfo app : fullList) {
                if (app.getLabel().toLowerCase().contains(lower)) {
                    filteredList.add(app);
                }
            }
        }
        notifyDataSetChanged();
    }

    private static class ViewHolder {
        ImageView ivIcon;
        TextView tvName;
    }
}
