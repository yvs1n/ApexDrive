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

    public AppsGridAdapter(Context context, List<AppInfo> apps) {
        this.fullList = new ArrayList<>(apps);
        this.filteredList = new ArrayList<>(apps);
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.clickListener = listener;
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
        if (currentQuery.trim().isEmpty()) {
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
