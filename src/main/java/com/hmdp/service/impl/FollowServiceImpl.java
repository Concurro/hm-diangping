package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Override
    public Result follow(Long id, Boolean isFollow) {
        if (isFollow) {
            // 添加关注
            concern(id);
        } else {
            // 取消关注
            cancelConcern(id);
        }
        return Result.ok();
    }

    private void cancelConcern(Long id) {
        Long userId = UserHolder.getUser().getId();
        remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
    }

    private void concern(Long id) {
        Long userId = UserHolder.getUser().getId();
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(id);
        save(follow);
    }

    @Override
    public Result isFollow(Long id) {
        return Result.ok(isFollow_w(id));
    }

    private boolean isFollow_w(Long id) {
        Long userId = UserHolder.getUser().getId();
        return query().eq("user_id", userId).eq("follow_user_id", id).count() > 0;
    }
}
