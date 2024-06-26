package com.ssafy.BonVoyage.plan.service.impl;

import com.ssafy.BonVoyage.auth.domain.Member;
import com.ssafy.BonVoyage.auth.repository.MemberRepository;
import com.ssafy.BonVoyage.group.domain.GroupWithMember;
import com.ssafy.BonVoyage.group.domain.TravelGroup;
import com.ssafy.BonVoyage.group.repository.GroupWithMemberRepository;
import com.ssafy.BonVoyage.group.repository.TravelGroupRepository;
import com.ssafy.BonVoyage.plan.domain.TravelPlan;
import com.ssafy.BonVoyage.plan.dto.TravelPlanDto;
import com.ssafy.BonVoyage.plan.dto.response.TravelPlanListResponse;
import com.ssafy.BonVoyage.plan.repository.DetailPlanRepository;
import com.ssafy.BonVoyage.plan.repository.TravelPlanRepository;
import com.ssafy.BonVoyage.plan.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TravelPlanServiceImpl implements TravelPlanService {

    private final TravelPlanRepository planRepository;
    private final TravelGroupRepository groupRepository;
    private final MemberRepository memberRepository;
    private final DetailPlanRepository detailPlanRepository;

    @Override
    @Transactional
    public Long create(TravelPlanDto dto) {
        TravelGroup group = groupRepository.findById(dto.getTravelGroupId())
                .orElseThrow(() -> new IllegalArgumentException("해당 그룹이 존재하지 않습니다. id=" + dto.getTravelGroupId()));
        return planRepository.save(dto.toEntity(group)).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public TravelPlanDto read(Long id) {
        return TravelPlanDto.toDto(planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("해당 계획이 존재하지 않습니다. id=" + id)));
    }

    @Override
    @Transactional
    public TravelPlanDto update(Long planId, TravelPlanDto dto) {
        TravelGroup group = groupRepository.findById(dto.getTravelGroupId())
                .orElseThrow(() -> new IllegalArgumentException("해당 그룹이 존재하지 않습니다. id=" + dto.getTravelGroupId()));
        TravelPlan plan = planRepository.findById(planId).get();
        plan.update(dto.getPlanTitle(), dto.getStartDate(), dto.getEndDate(), dto.getBudget()); // jpa dirty checking
        return TravelPlanDto.toDto(planRepository.findById(plan.getId()).get());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        TravelPlan plan = planRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("해당 계획이 존재하지 않습니다. id=" + id));
        detailPlanRepository.deleteAllByTravelPlan(plan); // 관련된 detail plan 지우기
        planRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TravelPlanListResponse> list(String userEmail) {
        Member member = memberRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("해당 회원이 존재하지 않습니다. id=" + userEmail));

        List<TravelGroup> groups = groupRepository.findAllByMemberId(member.getId());
        log.info("member group count: {}", groups.size());

        List<TravelPlanListResponse> result = new ArrayList<>();

        for (TravelGroup group : groups) {
            List<TravelPlan> plans = planRepository.findByTravelGroup(group);
            for (TravelPlan plan : plans) {
                result.add(TravelPlanListResponse.toDto(group, plan));
            }
        }

        return result;
    }
}
