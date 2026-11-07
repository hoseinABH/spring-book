'use client';

import React, { useEffect, useState } from 'react';
import { Box, Chip, Typography } from '@mui/material';
import TimerIcon from '@mui/icons-material/Timer';

interface HoldTimerProps {
  expiresAt: string;
  onExpired: () => void;
}

export default function HoldTimer({ expiresAt, onExpired }: HoldTimerProps) {
  const [secondsRemaining, setSecondsRemaining] = useState<number>(0);

  useEffect(() => {
    const calculateRemaining = () => {
      const remaining = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
      setSecondsRemaining(remaining);
      if (remaining === 0) {
        onExpired();
      }
    };

    calculateRemaining();
    const interval = setInterval(calculateRemaining, 1000);
    return () => clearInterval(interval);
  }, [expiresAt, onExpired]);

  const mins = Math.floor(secondsRemaining / 60);
  const secs = secondsRemaining % 60;
  const isUrgent = secondsRemaining < 120; // < 2 mins

  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1 }}>
      <Chip
        icon={<TimerIcon sx={{ animation: isUrgent ? 'pulse 1s infinite' : 'none' }} />}
        label={`Holding: ${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`}
        color={isUrgent ? 'error' : 'warning'}
        variant="filled"
        sx={{
          fontWeight: 700,
          fontSize: '0.95rem',
          px: 1,
          py: 0.5,
          boxShadow: isUrgent ? '0 0 12px rgba(239, 68, 68, 0.5)' : 'none',
        }}
      />
    </Box>
  );
}
